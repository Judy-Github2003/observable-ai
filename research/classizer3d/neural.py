"""Prototype: compositional typed-object embeddings and routed forward-only 3D DAG.

Not a pretrained LLM, not a spatially isotropic operator, and not a validated
replacement for standard attention. PyTorch is optional for the schema module.
"""
from __future__ import annotations

from collections import defaultdict
import math

import torch
from torch import nn

try:
    from .schema import Classizer, Node, Ref
except ImportError:  # direct execution from research/classizer3d/
    from schema import Classizer, Node, Ref


class MultiViewClassEmbedding(nn.Module):
    """Composable, inspectable type/lexical/property/relation/inheritance views.

    The byte vocabulary is fixed. New schema and field names reuse the encoder;
    the output stays differentiable. Views are explicit but NOT proof that a
    downstream model has learned the intended human semantics.
    """

    VIEWS = ("type", "lexical", "property", "relation", "inheritance")

    def __init__(self, width: int):
        super().__init__()
        self.bytes = nn.Embedding(256, width)
        self.number = nn.Sequential(nn.Linear(1, width), nn.Tanh(), nn.Linear(width, width))
        self.projections = nn.ModuleDict({"view_" + name: nn.Linear(width, width, bias=False) for name in self.VIEWS})
        self.view_logits = nn.Parameter(torch.zeros(len(self.VIEWS)))
        self.layernorm = nn.LayerNorm(width)

    def text(self, value: str) -> torch.Tensor:
        ids = torch.tensor(list(value.encode("utf-8")) or [0], dtype=torch.long, device=self.bytes.weight.device)
        return self.bytes(ids).mean(dim=0)

    def atom(self, value: object) -> torch.Tensor:
        if type(value) in (float, int, bool):
            return self.number(torch.tensor([float(value)], dtype=self.bytes.weight.dtype, device=self.bytes.weight.device))
        if isinstance(value, str):
            return self.text(value)
        if isinstance(value, Ref):
            return self.text("<reference>")
        raise TypeError(f"Unsupported value type: {type(value).__name__}")

    def views(self, graph: Classizer, node: Node) -> dict[str, torch.Tensor]:
        result = {"type": self.text(node.type_name)}
        lexical, properties, relations = [], [], []
        for key, value in sorted(node.fields.items()):
            if isinstance(value, Ref):
                relations.append(self.text(key))
            elif key in ("surface", "name"):
                lexical.append(self.atom(value))
            else:
                properties.append(self.text(key) + self.atom(value))
        def pooled(parts: list[torch.Tensor]) -> torch.Tensor:
            return torch.stack(parts).mean(0) if parts else self.bytes.weight.new_zeros(self.bytes.embedding_dim)
        result["lexical"] = pooled(lexical)
        result["property"] = pooled(properties)
        result["relation"] = pooled(relations)
        result["inheritance"] = pooled([self.text(p) for p in graph.schemas[node.type_name].parents])
        return result

    def encode_node(self, graph: Classizer, node: Node) -> torch.Tensor:
        view_values = self.views(graph, node)
        # Absent views are exactly zero and do not consume any attention mass.
        included = [name for name in self.VIEWS if name == "type" or bool(
            (name == "lexical" and any(k in node.fields for k in ("surface", "name"))) or
            (name == "property" and any(k not in ("surface", "name") and not isinstance(v, Ref)
                                        for k, v in node.fields.items())) or
            (name == "relation" and any(isinstance(v, Ref) for v in node.fields.values())) or
            (name == "inheritance" and graph.schemas[node.type_name].parents)
        )]
        indices = [self.VIEWS.index(name) for name in included]
        weights = torch.softmax(self.view_logits[indices], dim=0)
        projections = torch.stack([self.projections["view_" + name](view_values[name]) for name in included])
        return self.layernorm((weights.unsqueeze(-1) * projections).sum(dim=0))

    def forward(self, graph: Classizer) -> torch.Tensor:
        if not graph.nodes:
            raise ValueError("Graph must contain at least one node")
        return torch.stack([self.encode_node(graph, node) for node in graph.nodes.values()])


# Prior name retained as a local alias for callers of the initial prototype.
ByteFieldEncoder = MultiViewClassEmbedding


def forward_3d(source: tuple[int, int, int], target: tuple[int, int, int]) -> bool:
    return all(s <= t for s, t in zip(source, target)) and source != target


class Sparse3DDAG(nn.Module):
    """Target-specific top-k relation attention on a monotone 3D computation DAG.

    Each target may attend only to explicit predecessor references. Inference
    can skip irrelevant edges; training uses differentiable weights on selected
    edges (top-k selection itself is discrete). Shared weights across all nodes.
    """

    def __init__(self, width: int = 32, top_k: int = 2):
        super().__init__()
        if width < 4 or top_k < 1:
            raise ValueError("width >= 4 and top_k >= 1 required")
        self.width = width
        self.top_k = top_k
        self.encoder = ByteFieldEncoder(width)
        self.q = nn.Linear(width, width, bias=False)
        self.k = nn.Linear(width, width, bias=False)
        self.v = nn.Linear(width, width, bias=False)
        self.out = nn.Linear(width, width, bias=False)
        self.norm = nn.LayerNorm(width)
        self.trace: dict[str, dict[str, object]] = {}

    @staticmethod
    def validate(graph: Classizer) -> None:
        if not graph.nodes:
            raise ValueError("Empty graph")
        for source, target, _ in graph.edges():
            if not forward_3d(graph.nodes[source].coord, graph.nodes[target].coord):
                raise ValueError(f"Edge violates monotone 3D forward schedule: {source} -> {target}")

    def forward(self, graph: Classizer) -> tuple[torch.Tensor, dict[str, torch.Tensor]]:
        self.validate(graph)
        names = list(graph.nodes)
        encoded = self.encoder(graph)
        order = sorted(names, key=lambda n: (sum(graph.nodes[n].coord), *graph.nodes[n].coord, n))
        incoming: dict[str, list[tuple[str, str]]] = defaultdict(list)
        for source, target, relation in graph.edges():
            incoming[target].append((source, relation))
        output: dict[str, torch.Tensor] = {}
        trace: dict[str, dict[str, object]] = {}
        for name in order:
            base = encoded[names.index(name)]
            candidates = sorted(incoming[name])
            if not candidates:
                output[name] = base
                trace[name] = {"candidates": 0, "selected": [], "weights": []}
                continue
            source_states = torch.stack([output[src] for src, _ in candidates])
            relation_states = torch.stack([self.encoder.text(rel) for _, rel in candidates])
            query = self.q(base)
            keys = self.k(source_states + relation_states)
            scores = (keys * query).sum(dim=-1) / math.sqrt(self.width)
            selected_indices = torch.topk(scores, min(self.top_k, len(candidates))).indices
            weights = torch.softmax(scores[selected_indices], dim=0)
            values = self.v(source_states[selected_indices] + relation_states[selected_indices])
            message = (weights.unsqueeze(-1) * values).sum(dim=0)
            output[name] = self.norm(base + self.out(message))
            selected = [candidates[i] for i in selected_indices.detach().cpu().tolist()]
            trace[name] = {"candidates": len(candidates), "selected": selected,
                           "weights": weights.detach().cpu().tolist()}
        self.trace = trace
        return torch.stack([output[name] for name in names]), output


if __name__ == "__main__":
    try:
        from .schema import parse_want_eat_example
    except ImportError:
        from schema import parse_want_eat_example
    torch.manual_seed(7)
    graph = parse_want_eat_example("我想吃苹果")
    model = Sparse3DDAG(width=32, top_k=1)
    vectors, states = model(graph)
    print(graph.to_json())
    print("node_vectors:", tuple(vectors.shape))
    print("desire_route:", model.trace["desire"])
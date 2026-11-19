"""Small, explicit, versionable typed-object IR; not a language understanding model."""
from __future__ import annotations

from dataclasses import dataclass, field
import json
import re
from typing import Any, Literal

Kind = Literal["str", "int", "float", "bool", "ref", "any"]


@dataclass(frozen=True)
class FieldSpec:
    kind: Kind
    required: bool = True


@dataclass(frozen=True)
class Schema:
    name: str
    fields: dict[str, FieldSpec]
    parents: tuple[str, ...] = ()


@dataclass(frozen=True)
class Ref:
    target: str


@dataclass
class Node:
    node_id: str
    type_name: str
    fields: dict[str, Any]
    coord: tuple[int, int, int] = (0, 0, 0)


class Classizer:
    """Schemas and instances are data, not generated/evaluated Python code."""

    def __init__(self) -> None:
        self.schemas: dict[str, Schema] = {}
        self.nodes: dict[str, Node] = {}

    def add_schema(self, schema: Schema) -> None:
        if not schema.name or schema.name in self.schemas:
            raise ValueError("Schema name must be unique and nonempty")
        if any(parent not in self.schemas for parent in schema.parents):
            raise ValueError("Parent schemas must be registered first")
        inherited = self._fields_for_parents(schema.parents)
        for key, spec in schema.fields.items():
            if key in inherited and inherited[key] != spec:
                raise ValueError(f"Conflicting inherited field: {key}")
        self.schemas[schema.name] = schema

    def _fields_for_parents(self, parents: tuple[str, ...]) -> dict[str, FieldSpec]:
        combined: dict[str, FieldSpec] = {}
        for name in parents:
            inherited = self.effective_fields(name)
            for key, spec in inherited.items():
                if key in combined and combined[key] != spec:
                    raise ValueError(f"Conflicting parent fields: {key}")
                combined[key] = spec
        return combined

    def effective_fields(self, name: str) -> dict[str, FieldSpec]:
        schema = self.schemas[name]
        return self._fields_for_parents(schema.parents) | schema.fields

    @staticmethod
    def _valid(value: Any, kind: Kind) -> bool:
        return {
            "str": lambda: isinstance(value, str),
            "int": lambda: type(value) is int,
            "float": lambda: type(value) in (float, int),
            "bool": lambda: type(value) is bool,
            "ref": lambda: isinstance(value, Ref),
            "any": lambda: isinstance(value, (str, bool, int, float, Ref)),
        }[kind]()

    def add_node(self, node: Node) -> None:
        if node.node_id in self.nodes:
            raise ValueError("Duplicate node ID")
        if node.type_name not in self.schemas:
            raise ValueError("Unknown schema")
        if len(node.coord) != 3 or any(type(i) is not int or i < 0 for i in node.coord):
            raise ValueError("Coordinates must be three nonnegative integers")
        expected = self.effective_fields(node.type_name)
        if set(node.fields) - set(expected):
            raise ValueError(f"Unknown fields: {set(node.fields) - set(expected)}")
        for key, spec in expected.items():
            if key not in node.fields:
                if spec.required:
                    raise ValueError(f"Missing required field: {key}")
            elif not self._valid(node.fields[key], spec.kind):
                raise TypeError(f"Field {key} requires {spec.kind}")
        for v in node.fields.values():
            if isinstance(v, Ref) and v.target not in self.nodes:
                raise ValueError(f"Reference must already exist: {v.target}")
        self.nodes[node.node_id] = node

    def edges(self) -> list[tuple[str, str, str]]:
        """Reference target -> referencing node: information flows from leaf to parent."""
        return [
            (v.target, node.node_id, key)
            for node in self.nodes.values()
            for key, v in node.fields.items()
            if isinstance(v, Ref)
        ]

    def to_json(self) -> str:
        def encode(v: Any) -> Any:
            return {"$ref": v.target} if isinstance(v, Ref) else v
        obj = {
            "format": "observable-ai-classizer3d-v0",
            "schemas": [
                {"name": s.name, "parents": list(s.parents),
                 "fields": {k: {"kind": v.kind, "required": v.required} for k, v in s.fields.items()}}
                for s in self.schemas.values()
            ],
            "nodes": [
                {"id": n.node_id, "type": n.type_name, "coord": list(n.coord),
                 "fields": {k: encode(v) for k, v in n.fields.items()}}
                for n in self.nodes.values()
            ],
        }
        return json.dumps(obj, ensure_ascii=False, sort_keys=True)

    @classmethod
    def from_json(cls, source: str) -> "Classizer":
        data = json.loads(source)
        if data.get("format") != "observable-ai-classizer3d-v0":
            raise ValueError("Unsupported format")
        model = cls()
        for s in data["schemas"]:
            model.add_schema(Schema(s["name"], {k: FieldSpec(**v) for k, v in s["fields"].items()}, tuple(s["parents"])))
        for n in data["nodes"]:
            fields = {k: Ref(v["$ref"]) if isinstance(v, dict) and set(v) == {"$ref"} else v
                      for k, v in n["fields"].items()}
            model.add_node(Node(n["id"], n["type"], fields, tuple(n["coord"])))
        return model


def parse_want_eat_example(text: str) -> Classizer:
    """Deliberately narrow, auditable grammar demonstration; NOT an open-domain parser."""
    graph = Classizer()
    graph.add_schema(Schema("Entity", {"surface": FieldSpec("str")}))
    graph.add_schema(Schema("Event", {"actor": FieldSpec("ref")}))
    graph.add_schema(Schema("Eat", {"object": FieldSpec("ref")}, ("Event",)))
    graph.add_schema(Schema("Desire", {"experiencer": FieldSpec("ref"), "event": FieldSpec("ref")}))
    graph.add_schema(Schema("RawText", {"surface": FieldSpec("str")}))
    match = re.fullmatch(r"(.{1,8}?)想吃(.{1,20})", text.strip())
    if match is None:
        graph.add_node(Node("raw", "RawText", {"surface": text}, (0, 0, 0)))
        return graph
    subject, obj = match.groups()
    graph.add_node(Node("subject", "Entity", {"surface": subject}, (0, 0, 0)))
    graph.add_node(Node("object", "Entity", {"surface": obj}, (0, 1, 0)))
    graph.add_node(Node("eat", "Eat", {"actor": Ref("subject"), "object": Ref("object")}, (1, 1, 0)))
    graph.add_node(Node("desire", "Desire", {"experiencer": Ref("subject"), "event": Ref("eat")}, (2, 1, 1)))
    return graph
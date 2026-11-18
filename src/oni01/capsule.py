# Codex, 2026-11-17：为 ONI-01 适配已验证的最近证据规则与输入协议。
"""Parse controlled facts and apply the learned three-edge inheritance rule.

Rule provenance: semantics/3/nearest in OA-NEXT-02-CAPABILITY-01,
documented in docs/research/oa-next-02/capability-01-results.md.
"""

from collections import defaultdict
from collections.abc import Iterable
from dataclasses import dataclass
from hashlib import sha256
import re
from typing import Literal


RULE_ID = "semantic.nearest_inheritance.v1"
RULE_VERSION = "1"
MAX_DEPTH = 3

_ATOM = r"\w[\w.-]*"
_PROMPT = re.compile(
    r"\[ONI_FACTS\]\r?\n(?P<facts>(?:[^\r\n]+\r?\n)*)"
    r"(?P<query_marker>\[ONI_QUERY\])\r?\n"
    rf"(?P<query>PROPERTY (?P<entity>{_ATOM}) (?P<key>{_ATOM}))\r?\n"
    r"\[ONI_ANSWER\](?:\r?\n)?"
)
_PARENT = re.compile(rf"PARENT ({_ATOM}) ({_ATOM})")
_PROPERTY = re.compile(rf"PROPERTY ({_ATOM}) ({_ATOM}) ({_ATOM})")


@dataclass(frozen=True)
class Context:
    parents: tuple[tuple[str, str], ...]
    properties: tuple[tuple[str, str, str], ...]
    query_entity: str
    property_key: str
    # Half-open character offsets in the original prompt for token alignment.
    query_span: tuple[int, int]
    query_marker_span: tuple[int, int]
    input_hash: str


@dataclass(frozen=True)
class Evidence:
    value: str
    source: str
    path: tuple[str, ...]


@dataclass(frozen=True)
class Capsule:
    status: Literal["FOUND", "UNKNOWN", "AMBIGUOUS"]
    query_entity: str
    property_key: str
    input_hash: str
    value: str | None = None
    evidence_source: str | None = None
    inheritance_depth: int | None = None
    path: tuple[str, ...] = ()
    rule_id: str = RULE_ID
    rule_version: str = RULE_VERSION
    evidence_status: str = "VERIFIED"
    conflicting_evidence: tuple[Evidence, ...] = ()


def render_prompt(facts: Iterable[str], query: str) -> str:
    """Serialize the public statements and query up to the answer marker."""
    return "\n".join(("[ONI_FACTS]", *facts, "[ONI_QUERY]", query, "[ONI_ANSWER]"))


def parse_prompt(prompt: str) -> Context | None:
    """Return a context only for the complete controlled prompt envelope."""
    match = _PROMPT.fullmatch(prompt)
    if match is None:
        return None

    parents = []
    properties = []
    for raw_line in match["facts"].split("\n")[:-1]:
        line = raw_line.removesuffix("\r")
        if parent := _PARENT.fullmatch(line):
            parents.append(parent.groups())
        elif prop := _PROPERTY.fullmatch(line):
            properties.append(prop.groups())
        else:
            # Malformed input never contributes partial facts to a capsule.
            return None

    return Context(
        parents=tuple(parents),
        properties=tuple(properties),
        query_entity=match["entity"],
        property_key=match["key"],
        query_span=match.span("query"),
        query_marker_span=match.span("query_marker"),
        input_hash=sha256(prompt.encode("utf-8")).hexdigest(),
    )


def solve(context: Context) -> Capsule:
    """Resolve the nearest distance layer with evidence, through three edges."""
    parents: dict[str, set[str]] = defaultdict(set)
    for child, parent in context.parents:
        parents[child].add(parent)

    values: dict[str, set[str]] = defaultdict(set)
    for entity, key, value in context.properties:
        if key == context.property_key:
            values[entity].add(value)

    query_fields = {
        "query_entity": context.query_entity,
        "property_key": context.property_key,
        "input_hash": context.input_hash,
    }
    frontier = {context.query_entity: (context.query_entity,)}
    seen = set(frontier)
    for depth in range(MAX_DEPTH + 1):
        evidence = tuple(
            Evidence(value, entity, frontier[entity])
            for entity in sorted(frontier)
            for value in sorted(values.get(entity, ()))
        )
        if evidence:
            # Inspect the entire nearest layer before selecting evidence.
            if len({claim.value for claim in evidence}) > 1:
                return Capsule(
                    "AMBIGUOUS",
                    inheritance_depth=depth,
                    conflicting_evidence=evidence,
                    **query_fields,
                )
            claim = evidence[0]
            return Capsule(
                "FOUND",
                value=claim.value,
                evidence_source=claim.source,
                inheritance_depth=depth,
                path=claim.path,
                **query_fields,
            )
        if not frontier or depth == MAX_DEPTH:
            break

        next_frontier = {}
        for entity, path in frontier.items():
            for parent in sorted(parents.get(entity, ())):
                # The first visit gives the shortest path and bounds cycles.
                if parent not in seen:
                    seen.add(parent)
                    next_frontier[parent] = (*path, parent)
        frontier = next_frontier

    return Capsule("UNKNOWN", **query_fields)

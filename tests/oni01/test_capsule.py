# Codex, 2026-11-17：验证 ONI-01 的输入边界、最近证据和弃答。
from hashlib import sha256
import unittest

from src.oni01.capsule import parse_prompt, render_prompt, solve


class CapsuleTests(unittest.TestCase):
    def resolve(self, facts, query="PROPERTY cub color"):
        context = parse_prompt(render_prompt(facts, query))
        self.assertIsNotNone(context)
        return solve(context)

    def test_nearest_evidence_keeps_its_provenance(self):
        capsule = self.resolve(
            [
                "PARENT cub bear",
                "PARENT bear animal",
                "PROPERTY animal color gray",
                "PROPERTY bear diet omnivore",
                "PROPERTY bear color brown",
            ]
        )
        self.assertEqual(capsule.status, "FOUND")
        self.assertEqual(capsule.value, "brown")
        self.assertEqual(capsule.evidence_source, "bear")
        self.assertEqual(capsule.inheritance_depth, 1)
        self.assertEqual(capsule.path, ("cub", "bear"))
        self.assertEqual(capsule.rule_id, "semantic.nearest_inheritance.v1")
        self.assertEqual(capsule.rule_version, "1")
        self.assertEqual(capsule.evidence_status, "VERIFIED")

    def test_own_property_overrides_conflicting_parents(self):
        capsule = self.resolve(
            [
                "PARENT cub bear",
                "PARENT cub fox",
                "PROPERTY bear color brown",
                "PROPERTY fox color red",
                "PROPERTY cub color dark",
            ]
        )
        self.assertEqual((capsule.value, capsule.inheritance_depth), ("dark", 0))
        self.assertEqual(capsule.path, ("cub",))

    def test_same_layer_conflict_keeps_both_claims_and_abstains(self):
        capsule = self.resolve(
            [
                "PARENT cub fox",
                "PARENT cub bear",
                "PROPERTY fox color red",
                "PROPERTY bear color brown",
                "PROPERTY bear color brown",
            ]
        )
        self.assertEqual(capsule.status, "AMBIGUOUS")
        self.assertIsNone(capsule.value)
        self.assertIsNone(capsule.evidence_source)
        self.assertEqual(capsule.inheritance_depth, 1)
        self.assertEqual(
            [(claim.value, claim.path) for claim in capsule.conflicting_evidence],
            [("brown", ("cub", "bear")), ("red", ("cub", "fox"))],
        )

    def test_agreement_between_multiple_parents_is_not_a_conflict(self):
        capsule = self.resolve(
            [
                "PARENT cub fox",
                "PARENT cub bear",
                "PROPERTY fox color brown",
                "PROPERTY bear color brown",
            ]
        )
        self.assertEqual((capsule.status, capsule.value), ("FOUND", "brown"))
        self.assertEqual(capsule.path, ("cub", "bear"))

    def test_three_edge_boundary_and_cycle(self):
        graph = [
            "PARENT cub bear",
            "PARENT bear mammal",
            "PARENT mammal cub",
            "PARENT mammal animal",
            "PARENT animal organism",
        ]
        allowed = self.resolve([*graph, "PROPERTY animal color gray"])
        self.assertEqual(allowed.inheritance_depth, 3)
        self.assertEqual(allowed.path, ("cub", "bear", "mammal", "animal"))
        distant = self.resolve([*graph, "PROPERTY organism color gray"])
        self.assertEqual(distant.status, "UNKNOWN")
        self.assertIsNone(distant.value)
        self.assertIsNone(distant.inheritance_depth)
        self.assertEqual(distant.path, ())
        self.assertEqual(self.resolve(graph[:3]).status, "UNKNOWN")

    def test_current_input_alone_controls_the_outcome(self):
        first = self.resolve(["PROPERTY cub color brown"])
        second = self.resolve(["PROPERTY cub age young"])
        self.assertEqual((first.status, first.value), ("FOUND", "brown"))
        self.assertEqual(second.status, "UNKNOWN")
        self.assertNotEqual(first.input_hash, second.input_hash)
        self.assertEqual(self.resolve([]).status, "UNKNOWN")

    def test_parser_tracks_exact_prompt_and_query_offsets(self):
        query = "PROPERTY 麻雀 color"
        prompt = render_prompt(["PARENT 麻雀 bird", "PROPERTY bird color blue"], query)
        prompt = prompt.replace("\n", "\r\n") + "\r\n"
        parsed = parse_prompt(prompt)
        self.assertIsNotNone(parsed)
        self.assertEqual(prompt[slice(*parsed.query_span)], query)
        self.assertEqual(prompt[slice(*parsed.query_marker_span)], "[ONI_QUERY]")
        self.assertEqual(parsed.parents, (("麻雀", "bird"),))
        self.assertEqual(parsed.properties, (("bird", "color", "blue"),))
        self.assertEqual(parsed.input_hash, sha256(prompt.encode("utf-8")).hexdigest())
        self.assertEqual(solve(parsed).input_hash, parsed.input_hash)

    def test_parser_requires_the_whole_sanctioned_envelope(self):
        empty = render_prompt([], "PROPERTY cub color")
        malformed = (
            "What color is a cub?",
            "prefix\n" + empty,
            empty + "\nbrown",
            empty.replace("PROPERTY cub color", "PROPERTY cub color brown"),
            render_prompt(["PARENT cub"], "PROPERTY cub color"),
            render_prompt(["EXPECTED brown"], "PROPERTY cub color"),
            render_prompt(["PARENT cub bear\vPROPERTY bear color brown"], "PROPERTY cub color"),
            render_prompt(["PROPERTY bear color brown"], "PROPERTY cub"),
        )
        for prompt in malformed:
            with self.subTest(prompt=prompt):
                self.assertIsNone(parse_prompt(prompt))


if __name__ == "__main__":
    unittest.main()

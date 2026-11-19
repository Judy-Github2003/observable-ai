import unittest

from research.classizer3d.schema import Classizer, FieldSpec, Node, Ref, Schema, parse_want_eat_example


class SchemaTests(unittest.TestCase):
    def test_example_has_modality_and_correct_roles(self):
        graph = parse_want_eat_example("我想吃苹果")
        self.assertEqual(graph.nodes["desire"].type_name, "Desire")
        self.assertEqual(graph.nodes["eat"].fields["actor"], Ref("subject"))
        self.assertEqual(graph.nodes["eat"].fields["object"], Ref("object"))
        self.assertFalse("occurred" in graph.nodes["eat"].fields)
        self.assertEqual(len(graph.edges()), 4)

    def test_unknown_sentence_kept_verbatim(self):
        graph = parse_want_eat_example("今天阴天")
        self.assertEqual(graph.nodes["raw"].fields["surface"], "今天阴天")

    def test_roundtrip(self):
        graph = parse_want_eat_example("张三想吃橘子")
        restored = Classizer.from_json(graph.to_json())
        self.assertEqual(restored.to_json(), graph.to_json())

    def test_inheritance_and_dynamic_type_registration(self):
        graph = Classizer()
        graph.add_schema(Schema("Item", {"name": FieldSpec("str")}))
        graph.add_schema(Schema("FreshFood", {"fresh": FieldSpec("bool")}, ("Item",)))
        graph.add_node(Node("fruit", "FreshFood", {"name": "梨", "fresh": True}))
        self.assertEqual(set(graph.effective_fields("FreshFood")), {"name", "fresh"})
        with self.assertRaises(TypeError):
            graph.add_node(Node("bad", "FreshFood", {"name": "梨", "fresh": "yes"}))

    def test_references_must_exist(self):
        graph = Classizer()
        graph.add_schema(Schema("Link", {"target": FieldSpec("ref")}))
        with self.assertRaises(ValueError):
            graph.add_node(Node("a", "Link", {"target": Ref("missing")}))


if __name__ == "__main__":
    unittest.main()
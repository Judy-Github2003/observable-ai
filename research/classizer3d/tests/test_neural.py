import unittest

import torch

from research.classizer3d.neural import Sparse3DDAG, forward_3d
from research.classizer3d.schema import Classizer, FieldSpec, Node, Ref, Schema, parse_want_eat_example


class NeuralTests(unittest.TestCase):
    def setUp(self):
        torch.manual_seed(123)

    def test_nontrivial_3d_forward_mask(self):
        self.assertTrue(forward_3d((0, 1, 0), (2, 1, 1)))
        self.assertFalse(forward_3d((1, 0, 1), (0, 2, 2)))
        self.assertFalse(forward_3d((0, 0, 0), (0, 0, 0)))

    def test_topk_sparse_and_shapes(self):
        g = parse_want_eat_example("我想吃苹果")
        model = Sparse3DDAG(32, top_k=1)
        y, states = model(g)
        self.assertEqual(y.shape, (4, 32))
        self.assertEqual(set(states), set(g.nodes))
        self.assertEqual(model.trace["eat"]["candidates"], 2)
        self.assertEqual(len(model.trace["eat"]["selected"]), 1)
        self.assertEqual(model.trace["desire"]["candidates"], 2)
        self.assertEqual(len(model.trace["desire"]["selected"]), 1)

    def test_backward_edge_rejected(self):
        g = Classizer()
        g.add_schema(Schema("Atom", {"label": FieldSpec("str"), "parent": FieldSpec("ref", required=False)}))
        g.add_node(Node("a", "Atom", {"label": "a"}, (1, 0, 1)))
        g.add_node(Node("b", "Atom", {"label": "b", "parent": Ref("a")}, (0, 1, 2)))
        with self.assertRaises(ValueError):
            Sparse3DDAG.validate(g)

    def test_gradient_into_encoder_and_route(self):
        g = parse_want_eat_example("我想吃苹果")
        model = Sparse3DDAG(32, top_k=2)
        y, state = model(g)
        loss = state["desire"].square().mean() + state["eat"].sum()
        loss.backward()
        self.assertGreater(model.encoder.bytes.weight.grad.abs().sum().item(), 0)
        self.assertGreater(model.q.weight.grad.abs().sum().item(), 0)
        self.assertGreater(model.v.weight.grad.abs().sum().item(), 0)

    def test_multiview_channels_and_inheritance(self):
        graph = Classizer()
        graph.add_schema(Schema("Thing", {"surface": FieldSpec("str")}))
        graph.add_schema(Schema("Fruit", {"color": FieldSpec("str")}, ("Thing",)))
        graph.add_node(Node("fruit", "Fruit", {"surface": "苹果", "color": "红"}))
        model = Sparse3DDAG(32)
        views = model.encoder.views(graph, graph.nodes["fruit"])
        self.assertEqual(set(views), set(model.encoder.VIEWS))
        self.assertTrue(all(v.shape == (32,) for v in views.values()))
        self.assertGreater(views["inheritance"].abs().sum().item(), 0)
        self.assertGreater(views["lexical"].abs().sum().item(), 0)
        self.assertGreater(views["property"].abs().sum().item(), 0)

    def test_unseen_class_without_new_parameters(self):
        g = Classizer()
        g.add_schema(Schema("NovelType_2026", {"new_field": FieldSpec("str")}))
        g.add_node(Node("novel", "NovelType_2026", {"new_field": "新概念"}))
        model = Sparse3DDAG(32)
        before = sum(p.numel() for p in model.parameters())
        y, _ = model(g)
        self.assertEqual(y.shape, (1, 32))
        self.assertEqual(sum(p.numel() for p in model.parameters()), before)

    def test_leaf_change_propagates(self):
        model = Sparse3DDAG(32, top_k=2)
        a = parse_want_eat_example("我想吃苹果")
        b = parse_want_eat_example("我想吃香蕉")
        _, out_a = model(a)
        _, out_b = model(b)
        self.assertGreater((out_a["desire"] - out_b["desire"]).abs().sum().item(), 0)


if __name__ == "__main__":
    unittest.main()
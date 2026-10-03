import unittest
import xml.etree.ElementTree as ET
from types import SimpleNamespace

from overdrive_driver import snapshot


class ClueParsingTest(unittest.TestCase):
    def test_next_shape_preview_does_not_replace_the_current_clue(self):
        for suffix in ("", ". Next: Triangle"):
            root = ET.Element("hierarchy")
            ET.SubElement(root, "node", {"content-desc": f"Wave 9. Clue for Maya: Circle{suffix}"})
            ET.SubElement(root, "node", {"content-desc": "Rotate clockwise. Top: Diamond. Clockwise edges: Diamond, Cross, Triangle, Circle"})
            view = snapshot(SimpleNamespace(nodes=lambda: root))
            self.assertEqual(9, view["wave"])
            self.assertEqual("Circle", view["clue"])


if __name__ == "__main__":
    unittest.main()

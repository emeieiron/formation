import unittest
import xml.etree.ElementTree as ET
from types import SimpleNamespace

from mosaic_driver import place, read


class PieceParsingTest(unittest.TestCase):
    def test_labels_map_to_grid_positions(self):
        self.assertEqual((0, 0), place("Mosaic piece: Top bar · left half", 2))
        self.assertEqual((1, 1), place("Mosaic piece: Middle bar · right half", 2))
        self.assertEqual((2, 1), place("Mosaic piece: Bottom bar · centre", 3))
        self.assertEqual((2, 2), place("Mosaic piece: Bottom bar · right end", 3))
        self.assertEqual((1, 3), place("Mosaic piece: Middle bar · piece 4 of 6", 6))

    def test_seam_strips_report_their_state(self):
        root = ET.Element("hierarchy")
        ET.SubElement(root, "node", {"content-desc": "Mosaic piece: Top bar · left half", "bounds": "[0,0][1344,2992]"})
        ET.SubElement(root, "node", {"content-desc": "Right seam, sealed", "bounds": "[0,2800][1344,2950]"})
        ET.SubElement(root, "node", {"content-desc": "Bottom seam, open", "bounds": "[0,0][150,2700]"})
        piece, strips = read(SimpleNamespace(nodes=lambda: root))
        self.assertIsNotNone(piece)
        self.assertEqual({"Right": True, "Bottom": False}, {edge: sealed for edge, (_, sealed) in strips.items()})


if __name__ == "__main__":
    unittest.main()

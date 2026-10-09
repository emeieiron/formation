import unittest
import xml.etree.ElementTree as ET
from unittest.mock import Mock, patch

import android_layout


class LayoutRecoveryTest(unittest.TestCase):
    def setUp(self):
        android_layout._readers.clear()

    def tearDown(self):
        android_layout._readers.clear()

    def test_initial_reader_failure_uses_the_fallback(self):
        screen = ET.Element("hierarchy")
        with patch.object(android_layout, "Reader", side_effect=android_layout.LayoutUnavailable("unavailable")), patch.object(android_layout, "fallback_nodes", return_value=screen) as fallback:
            self.assertIs(screen, android_layout.nodes("test-device"))
            fallback.assert_called_once_with("test-device")
        self.assertNotIn("test-device", android_layout._readers)

    def test_empty_layout_closes_the_reader_before_fallback(self):
        reader = Mock()
        reader.read.return_value = []
        android_layout._readers["test-device"] = reader
        screen = ET.Element("hierarchy")
        with patch.object(android_layout, "fallback_nodes", return_value=screen), patch.object(android_layout.time, "sleep"):
            self.assertIs(screen, android_layout.nodes("test-device"))
        reader.close.assert_called_once()
        self.assertNotIn("test-device", android_layout._readers)

    def test_a_starting_activity_can_publish_its_root_without_restarting_instrumentation(self):
        reader = Mock()
        reader.read.side_effect = [[], [{"text": "YOUR STEPS", "bounds": "[0,0][100,50]"}]]
        android_layout._readers["test-device"] = reader
        with patch.object(android_layout.time, "sleep"):
            root = android_layout.nodes("test-device")
        self.assertEqual("YOUR STEPS", next(root.iter("node")).get("text"))
        self.assertEqual(2, reader.read.call_count)
        reader.close.assert_not_called()

    def test_supported_cli_converts_the_current_accessible_layout(self):
        result = Mock(stdout='[{"text":"Tap to Step","bounds":"[0,0][100,50]","interactions":["CLICKABLE"]}]')
        with patch.object(android_layout.subprocess, "run", return_value=result) as command:
            root = android_layout.cli_nodes("test-device")
        self.assertEqual("Tap to Step", next(root.iter("node")).get("text"))
        self.assertEqual("android", command.call_args.args[0][0])
        self.assertIn("--no-idle", command.call_args.args[0])

    def test_readable_layout_reuses_the_reader(self):
        reader = Mock()
        reader.read.return_value = [{"text": "Tap to Step", "bounds": "[0,0][100,50]", "interactions": ["CLICKABLE"]}]
        android_layout._readers["test-device"] = reader
        root = android_layout.nodes("test-device")
        node = next(root.iter("node"))
        self.assertEqual("Tap to Step", node.get("text"))
        self.assertEqual("true", node.get("clickable"))
        reader.close.assert_not_called()


if __name__ == "__main__":
    unittest.main()

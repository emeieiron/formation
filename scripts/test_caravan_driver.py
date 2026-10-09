import unittest
import xml.etree.ElementTree as ET
from types import SimpleNamespace
from unittest.mock import Mock, patch

from e2e import Failed, apk_matches, install_build, verify_participant_identities
from caravan_driver import CaravanFailed, play_caravan, read_my_steps, read_roster_steps


def layout(*rows):
    root = ET.Element("hierarchy")
    for labels in rows:
        row = ET.SubElement(root, "node")
        for label in labels:
            ET.SubElement(row, "node", {"text": label})
    return root


class CaravanDriverTest(unittest.TestCase):
    def test_personal_count_uses_the_personal_card_not_roster_or_progress(self):
        root = layout(("CARAVAN", "16 / 15000"), ("YOUR STEPS", "31", "Goal: 15000 steps"), ("You", "31 st"))
        phone = SimpleNamespace(role="host", nodes=lambda: root)
        self.assertEqual(31, read_my_steps(phone))

    def test_missing_personal_count_is_a_failure_instead_of_zero(self):
        phone = SimpleNamespace(role="host", nodes=lambda: layout(("Caravan", "16 / 15000")))
        with self.assertRaises(CaravanFailed):
            read_my_steps(phone)

    def test_roster_counts_belong_to_the_named_walker(self):
        root = layout(("Theo", "Leading", "30 st"), ("You", "Lagging", "16 st"))
        self.assertEqual(30, read_roster_steps(root, "Theo"))
        self.assertEqual(16, read_roster_steps(root, "You"))

    def test_flat_accessibility_labels_use_the_same_rendered_row(self):
        root = ET.Element("hierarchy")
        for text, area in (("Theo", "[150,1728][267,1800]"), ("5 st", "[1185,1740][1248,1788]"),
                           ("Maya", "[150,1872][267,1944]"), ("0 st", "[1185,1884][1248,1932]")):
            ET.SubElement(root, "node", {"text": text, "bounds": area})
        self.assertEqual(5, read_roster_steps(root, "Theo"))
        self.assertEqual(0, read_roster_steps(root, "Maya"))

    def test_missing_or_ambiguous_roster_count_is_a_failure(self):
        with self.assertRaises(CaravanFailed):
            read_roster_steps(layout(("Theo", "16 st", "30 st")), "Theo")
        with self.assertRaises(CaravanFailed):
            read_roster_steps(layout(("You", "16 st")), "Theo")

    def test_driver_rejects_incomplete_groups_before_touching_a_phone(self):
        phone = Mock()
        with self.assertRaises(CaravanFailed):
            play_caravan([phone] * 5, 6, Mock())
        phone.nodes.assert_not_called()


class ParticipantIdentityTest(unittest.TestCase):
    def test_cloned_installations_are_rejected_before_joining(self):
        phones = [Mock(pref=Mock(return_value="same-installation")) for _ in range(6)]
        with self.assertRaisesRegex(Failed, "duplicated"):
            verify_participant_identities(phones)

    def test_unique_installations_are_accepted(self):
        phones = [Mock(pref=Mock(return_value=f"installation-{index}")) for index in range(6)]
        verify_participant_identities(phones)

    def test_missing_installation_is_rejected(self):
        with self.assertRaisesRegex(Failed, "initialized"):
            verify_participant_identities([Mock(pref=Mock(return_value=None))])


class VerifiedInstallTest(unittest.TestCase):
    def test_matching_apk_skips_a_redundant_install(self):
        phone = Mock(role="host", serial="emulator-test")
        with patch("e2e.apk_matches", return_value=True), patch("e2e.log"):
            install_build(phone, "expected-hash")
        phone.adb.assert_not_called()

    def test_install_is_checked_against_the_build_afterwards(self):
        phone = Mock(role="host", serial="emulator-test")
        with patch("e2e.apk_matches", side_effect=[False, True]), patch("e2e.log"):
            install_build(phone, "expected-hash")
        self.assertEqual("install", phone.adb.call_args.args[0])

    def test_wrong_installed_bytes_fail_the_journey(self):
        phone = Mock(role="host", serial="emulator-test")
        with patch("e2e.apk_matches", return_value=False), patch("e2e.log"):
            with self.assertRaises(Failed):
                install_build(phone, "expected-hash")

    def test_package_path_and_checksum_are_read_without_shell_interpolation(self):
        phone = Mock()
        phone.adb.side_effect = ["package:/data/app/~~abc/test-42/base.apk\n", "abc123  /data/app/~~abc/test-42/base.apk\n"]
        self.assertTrue(apk_matches(phone, "abc123"))
        self.assertEqual(("shell", "sha256sum", "/data/app/~~abc/test-42/base.apk"), phone.adb.call_args.args)


if __name__ == "__main__":
    unittest.main()

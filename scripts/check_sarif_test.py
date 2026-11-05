"""Exercise fail-closed scan review boundaries, including frozen diagnostic exceptions."""
import hashlib
import importlib.util
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path

spec = importlib.util.spec_from_file_location("gate", Path(__file__).with_name("check-sarif.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class SarifGateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.folder = self.root / "results"
        self.folder.mkdir()
        self.source = self.root / "Controller.java"
        self.source.write_text("read-only export with access audit")
        self.result = {"ruleId": "java/csrf-unprotected-request-type", "message": {"text": "Review GET"},
                       "locations": [{"physicalLocation": {"artifactLocation": {"uri": "Controller.java"},
                                                           "region": {"startLine": 5}}}]}

    def sarif(self, results=None, rules=True):
        rule = {"id": self.result["ruleId"], "properties": {"security-severity": "8.8"}}
        run = {"tool": {"driver": {}, "extensions": [{"rules": [rule] if rules else []}]},
               "results": [self.result] if results is None else results}
        (self.folder / "java.sarif").write_text(json.dumps({"runs": [run]}))

    def review(self):
        (self.root / "docs").mkdir()
        value = {"findings": [{"id": "audit-only", "rule": self.result["ruleId"], "severity": 8.8,
                              "path": "Controller.java", "line": 5, "reason": "Access logging only",
                              "sourceSha256": {"Controller.java": hashlib.sha256(self.source.read_bytes()).hexdigest()}}]}
        (self.root / "docs/codeql-reviewed-findings.json").write_text(json.dumps(value))

    def run_gate(self):
        with redirect_stdout(StringIO()):
            return gate.check(self.folder, self.root)

    def test_absent_output_is_not_a_pass(self):
        with self.assertRaises(ValueError):
            self.run_gate()

    def test_empty_completed_scan_passes(self):
        self.sarif([])
        self.assertEqual(self.run_gate(), 0)

    def test_extension_metadata_is_enforced_and_high_results_fail(self):
        self.sarif()
        self.assertEqual(self.run_gate(), 1)
        self.sarif(rules=False)
        with self.assertRaises(ValueError):
            self.run_gate()

    def test_exact_review_remains_visible_and_does_not_cover_moved_findings(self):
        self.sarif()
        self.review()
        output = StringIO()
        with redirect_stdout(output):
            self.assertEqual(gate.check(self.folder, self.root), 0)
        self.assertIn("1 reviewed diagnostic exception(s), 0 unresolved", output.getvalue())
        self.result["locations"][0]["physicalLocation"]["region"]["startLine"] = 6
        self.sarif()
        self.assertEqual(self.run_gate(), 1)

    def test_changed_source_requires_fresh_review(self):
        self.sarif()
        self.review()
        self.source.write_text("changed implementation")
        with self.assertRaisesRegex(ValueError, "Reviewed source changed"):
            self.run_gate()

    def test_review_cannot_hide_additional_or_duplicate_findings(self):
        self.review()
        other = json.loads(json.dumps(self.result))
        other["locations"][0]["physicalLocation"]["region"]["startLine"] = 9
        self.sarif([self.result, other])
        self.assertEqual(self.run_gate(), 1)
        self.sarif([self.result, self.result])
        with self.assertRaisesRegex(ValueError, "more than once"):
            self.run_gate()


if __name__ == "__main__":
    unittest.main()

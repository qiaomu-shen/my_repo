import json
import unittest

from dino_receiver import JumpEvent, parse_jump_event


class ParseJumpEventTest(unittest.TestCase):
    def test_parses_valid_event(self) -> None:
        payload = json.dumps(
            {
                "event": "JUMP",
                "sequence": 7,
                "phone_time_ns": 123456,
                "vertical_acceleration": 3.4,
            }
        ).encode()

        self.assertEqual(
            parse_jump_event(payload),
            JumpEvent(
                event="JUMP",
                sequence=7,
                phone_time_ns=123456,
                vertical_acceleration=3.4,
            ),
        )

    def test_rejects_wrong_event(self) -> None:
        payload = b'{"event":"DUCK","sequence":1,"phone_time_ns":2}'
        with self.assertRaisesRegex(ValueError, "unsupported event"):
            parse_jump_event(payload)

    def test_rejects_invalid_sequence(self) -> None:
        payload = b'{"event":"JUMP","sequence":-1,"phone_time_ns":2}'
        with self.assertRaisesRegex(ValueError, "sequence"):
            parse_jump_event(payload)

    def test_rejects_non_json(self) -> None:
        with self.assertRaisesRegex(ValueError, "UTF-8 JSON"):
            parse_jump_event(b"JUMP")


if __name__ == "__main__":
    unittest.main()

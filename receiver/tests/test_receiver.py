import json
import unittest

from dino_receiver import (
    ActionEvent,
    classify_event,
    parse_action_event,
    release_expired_actions,
)


class ParseActionEventTest(unittest.TestCase):
    def test_parses_valid_action(self) -> None:
        payload = json.dumps(
            {
                "event": "ACTION",
                "action": "JUMP_LEFT",
                "phase": "TRIGGER",
                "sequence": 7,
                "phone_time_ns": 123456,
                "confidence": 0.84,
                "vertical_acceleration": -8.9,
                "lateral_acceleration": -2.4,
                "session_id": "session-a",
            }
        ).encode()

        self.assertEqual(
            parse_action_event(payload),
            ActionEvent(
                event="ACTION",
                action="JUMP_LEFT",
                phase="TRIGGER",
                sequence=7,
                phone_time_ns=123456,
                confidence=0.84,
                vertical_acceleration=-8.9,
                lateral_acceleration=-2.4,
                session_id="session-a",
            ),
        )

    def test_maps_legacy_jump_to_jump_up(self) -> None:
        payload = b'{"event":"JUMP","sequence":1,"phone_time_ns":2}'
        event = parse_action_event(payload)
        self.assertEqual((event.action, event.phase), ("JUMP_UP", "TRIGGER"))

    def test_rejects_wrong_action_phase_combination(self) -> None:
        payload = b'{"event":"ACTION","action":"JUMP_UP","phase":"START","sequence":1,"phone_time_ns":2}'
        with self.assertRaisesRegex(ValueError, "TRIGGER"):
            parse_action_event(payload)

    def test_rejects_non_json(self) -> None:
        with self.assertRaisesRegex(ValueError, "UTF-8 JSON"):
            parse_action_event(b"JUMP")


class EventGateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.sequences: dict[tuple[str, str], int] = {}
        self.triggers: dict[tuple[str, str], int] = {}
        self.address = ("192.168.1.2", 5005)

    def event(
        self,
        sequence: int,
        *,
        action: str = "JUMP_UP",
        phase: str = "TRIGGER",
        session_id: str = "a",
    ) -> ActionEvent:
        return ActionEvent(
            event="ACTION",
            action=action,
            phase=phase,
            sequence=sequence,
            phone_time_ns=sequence,
            session_id=session_id,
        )

    def classify(self, event: ActionEvent, receive_ns: int) -> tuple[bool, str]:
        return classify_event(
            address=self.address,
            event=event,
            receive_ns=receive_ns,
            cooldown_ns=120_000_000,
            last_sequence_by_source=self.sequences,
            last_trigger_ns_by_source=self.triggers,
        )

    def test_sessions_have_independent_sequences(self) -> None:
        self.assertTrue(self.classify(self.event(4, session_id="a"), 1_000_000_000)[0])
        self.assertTrue(self.classify(self.event(0, session_id="b"), 1_200_000_000)[0])

    def test_duplicate_sequence_is_rejected(self) -> None:
        self.assertTrue(self.classify(self.event(4), 1_000_000_000)[0])
        accepted, reason = self.classify(self.event(4), 1_200_000_000)
        self.assertFalse(accepted)
        self.assertEqual(reason, "duplicate_or_out_of_order_sequence")

    def test_trigger_cooldown_does_not_block_continuous_stop(self) -> None:
        self.assertTrue(self.classify(self.event(1), 1_000_000_000)[0])
        stop = self.event(2, action="RUNNING", phase="STOP")
        self.assertTrue(self.classify(stop, 1_010_000_000)[0])


class RecordingOutput:
    def __init__(self) -> None:
        self.events: list[ActionEvent] = []

    def handle(self, event: ActionEvent) -> None:
        self.events.append(event)

    def release_all(self) -> None:
        pass


class WatchdogTest(unittest.TestCase):
    def test_expired_continuous_action_is_stopped(self) -> None:
        output = RecordingOutput()
        active = {"RUNNING": 100}
        expired = release_expired_actions(
            output=output,
            active_action_ns=active,
            now_ns=300,
            timeout_ns=150,
        )
        self.assertEqual(expired, ["RUNNING"])
        self.assertEqual(output.events[0].phase, "STOP")
        self.assertEqual(active, {})


if __name__ == "__main__":
    unittest.main()

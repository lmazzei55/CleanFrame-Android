"""Deterministic transport-free tests of the actual remote foreground confirmation."""
import asyncio
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from remote_wait import home_return


class FakeTV:
    def __init__(self, initial, events):
        self.current_app = initial
        self.events = list(events)
        self.callbacks = []
        self.keys = []
        self.now = 0

    def add_current_app_updated_callback(self, callback):
        self.callbacks.append(callback)

    def remove_current_app_updated_callback(self, callback):
        self.callbacks.remove(callback)

    def send_key_command(self, key):
        self.keys.append(key)
        assert self.callbacks, 'must subscribe before sending Home'

    async def sleep(self, seconds):
        end = self.now + seconds
        while self.events and self.events[0][0] <= end + 1e-8:
            self.now, self.current_app = self.events.pop(0)
            for callback in self.callbacks[:]:
                callback(self.current_app)
        self.now = end

    def check(self, **kwargs):
        return asyncio.run(home_return(self, 'cleanframe', clock=lambda: self.now, sleep=self.sleep, **kwargs))


class HomeReturnTests(unittest.TestCase):
    def test_google_tv_can_be_an_intermediate_screen(self):
        tv = FakeTV('netflix', [(.1, 'google'), (1.2, 'cleanframe')])
        self.assertEqual(tv.check(), (True, 'cleanframe'))
        self.assertGreaterEqual(tv.now, 2.2)
        self.assertEqual(tv.keys, ['HOME'])
        self.assertEqual(tv.callbacks, [])

    def test_cached_cleanframe_without_a_fresh_event_is_not_success(self):
        tv = FakeTV('cleanframe', [])
        self.assertEqual(tv.check(), (False, 'cleanframe'))

    def test_brief_cleanframe_flash_then_google_is_not_success(self):
        tv = FakeTV('netflix', [(.1, 'cleanframe'), (.5, 'google')])
        self.assertEqual(tv.check(), (False, 'google'))

    def test_return_after_three_seconds_still_gets_full_window(self):
        tv = FakeTV('netflix', [(.1, 'google'), (4, 'cleanframe')])
        self.assertEqual(tv.check(), (True, 'cleanframe'))

    def test_google_tv_that_stays_is_a_failure(self):
        tv = FakeTV('netflix', [(.1, 'google')])
        self.assertEqual(tv.check(), (False, 'google'))
        self.assertAlmostEqual(tv.now, 6)

    def test_return_too_close_to_deadline_is_not_confirmed_stable(self):
        tv = FakeTV('netflix', [(5.8, 'cleanframe')])
        self.assertEqual(tv.check(), (False, 'cleanframe'))

    def test_unknown_foreground_is_not_success(self):
        self.assertEqual(FakeTV(None, []).check(), (False, None))

    def test_subscriptions_are_cleaned_up_when_send_fails(self):
        tv = FakeTV('netflix', [])
        def fail(key):
            raise RuntimeError('disconnected')
        tv.send_key_command = fail
        with self.assertRaises(RuntimeError):
            tv.check()
        self.assertEqual(tv.callbacks, [])


if __name__ == '__main__':
    unittest.main()

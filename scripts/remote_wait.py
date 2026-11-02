"""Foreground confirmation independent of TV transport, pairing and CLI setup."""
import asyncio
import time


async def home_return(remote, target, seconds=6, stable_for=1, *, clock=time.monotonic, sleep=asyncio.sleep):
    """Press Home after subscribing; require a fresh target event and a stable foreground.

    A cached current_app value is not evidence of a successful Home return. Google TV
    may appear briefly before the accessibility service opens the custom launcher.
    """
    if seconds <= 0 or stable_for <= 0 or stable_for > seconds:
        raise ValueError('Invalid foreground confirmation interval')
    target_since = None

    def updated(app):
        nonlocal target_since
        if app != target:
            target_since = None
        elif target_since is None:
            target_since = clock()

    remote.add_current_app_updated_callback(updated)
    try:
        remote.send_key_command('HOME')
        deadline = clock() + seconds
        while True:
            now = clock()
            if target_since is not None and remote.current_app == target and now - target_since >= stable_for:
                return True, remote.current_app
            if now >= deadline:
                return False, remote.current_app
            await sleep(min(.1, deadline - now))
    finally:
        remote.remove_current_app_updated_callback(updated)

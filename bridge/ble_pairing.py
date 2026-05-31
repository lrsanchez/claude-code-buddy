#!/usr/bin/env python3
"""
BlueZ pairing helpers for Claude Buddy.

Provides a headless "Just Works" pairing agent (NoInputNoOutput capability)
so the daemon can bond with the Android peripheral without any PIN/passkey
prompt, plus helpers to pair and mark a device trusted (enabling BlueZ
auto-reconnect).

Bonding gives us two big reliability wins with flaky peripherals:
  1. IRK address resolution — BlueZ resolves the peripheral's rotating
     private address back to its identity, so we always find the right device.
  2. Trusted auto-connect — BlueZ silently re-establishes the encrypted link
     whenever the bonded device is back in range.
"""

import asyncio
import logging

from dbus_fast.aio import MessageBus
from dbus_fast.service import ServiceInterface, method
from dbus_fast import BusType, Variant

log = logging.getLogger("buddy")

AGENT_PATH    = "/com/claude/buddy/agent"
BLUEZ         = "org.bluez"
ADAPTER_PATH  = "/org/bluez/hci0"


class JustWorksAgent(ServiceInterface):
    """Auto-accepts all pairing requests (NoInputNoOutput → Just Works)."""

    def __init__(self):
        super().__init__("org.bluez.Agent1")

    @method()
    def Release(self):
        pass

    @method()
    def RequestPinCode(self, device: 'o') -> 's':
        return "0000"

    @method()
    def DisplayPinCode(self, device: 'o', pincode: 's'):
        pass

    @method()
    def RequestPasskey(self, device: 'o') -> 'u':
        return 0

    @method()
    def DisplayPasskey(self, device: 'o', passkey: 'u', entered: 'q'):
        pass

    @method()
    def RequestConfirmation(self, device: 'o', passkey: 'u'):
        # Numeric comparison — auto-confirm
        return

    @method()
    def RequestAuthorization(self, device: 'o'):
        return

    @method()
    def AuthorizeService(self, device: 'o', uuid: 's'):
        return

    @method()
    def Cancel(self):
        pass


def device_path(address: str, adapter: str = ADAPTER_PATH) -> str:
    return f"{adapter}/dev_" + address.upper().replace(":", "_")


class PairingManager:
    """Owns the system-bus connection, the agent, and pair/trust operations."""

    def __init__(self):
        self._bus: MessageBus | None = None
        self._agent = JustWorksAgent()
        self._registered = False

    async def setup(self) -> bool:
        """Connect to the system bus and register the Just Works agent as default."""
        try:
            self._bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
            self._bus.export(AGENT_PATH, self._agent)

            introspect = await self._bus.introspect(BLUEZ, "/org/bluez")
            obj = self._bus.get_proxy_object(BLUEZ, "/org/bluez", introspect)
            mgr = obj.get_interface("org.bluez.AgentManager1")

            # Re-registering throws if a stale agent exists; ignore that
            try:
                await mgr.call_register_agent(AGENT_PATH, "NoInputNoOutput")
            except Exception:
                pass
            try:
                await mgr.call_request_default_agent(AGENT_PATH)
            except Exception:
                pass

            self._registered = True
            return True
        except Exception as exc:
            log.debug(f"PairingManager.setup failed: {exc}")
            return False

    async def _device_iface(self, address: str):
        path = device_path(address)
        introspect = await self._bus.introspect(BLUEZ, path)
        obj = self._bus.get_proxy_object(BLUEZ, path, introspect)
        return obj.get_interface("org.bluez.Device1"), \
               obj.get_interface("org.freedesktop.DBus.Properties"), path

    async def is_paired(self, address: str) -> bool:
        try:
            _, props, _ = await self._device_iface(address)
            v = await props.call_get("org.bluez.Device1", "Paired")
            return bool(v.value)
        except Exception:
            return False

    async def pair_and_trust(self, address: str) -> bool:
        """Pair (if needed) and mark the device trusted for auto-reconnect."""
        if not self._bus:
            return False
        try:
            dev, props, _ = await self._device_iface(address)

            already = False
            try:
                v = await props.call_get("org.bluez.Device1", "Paired")
                already = bool(v.value)
            except Exception:
                pass

            if not already:
                try:
                    await dev.call_pair()
                    log.debug(f"Paired with {address}")
                except Exception as exc:
                    # AlreadyExists / in-progress are fine
                    if "AlreadyExists" not in str(exc):
                        log.debug(f"pair() note: {exc}")

            # Trust → BlueZ will auto-reconnect this device whenever it appears
            try:
                await props.call_set("org.bluez.Device1", "Trusted", Variant('b', True))
            except Exception as exc:
                log.debug(f"set Trusted note: {exc}")

            return True
        except Exception as exc:
            log.debug(f"pair_and_trust failed for {address}: {exc}")
            return False

    async def remove_device(self, address: str) -> bool:
        """Remove a stale bond (e.g. after the Android app was reinstalled)."""
        if not self._bus:
            return False
        try:
            introspect = await self._bus.introspect(BLUEZ, ADAPTER_PATH)
            obj = self._bus.get_proxy_object(BLUEZ, ADAPTER_PATH, introspect)
            adapter = obj.get_interface("org.bluez.Adapter1")
            await adapter.call_remove_device(device_path(address))
            log.debug(f"Removed stale bond for {address}")
            return True
        except Exception as exc:
            log.debug(f"remove_device note: {exc}")
            return False

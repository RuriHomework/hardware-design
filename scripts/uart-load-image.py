#!/usr/bin/env python3
import argparse
import os
import select
import struct
import sys
import termios
import time


BAUDS = {
    9600: termios.B9600,
    19200: termios.B19200,
    38400: termios.B38400,
    57600: termios.B57600,
    115200: termios.B115200,
}

IMEM_WORDS = 16384
DMEM_WORDS = 16384


def padded_words(path, limit_words):
    with open(path, "rb") as f:
        data = f.read()
    pad = (-len(data)) % 4
    if pad:
        data += b"\x00" * pad
    words = len(data) // 4
    if words > limit_words:
        raise SystemExit(f"{path} has {words} words, limit is {limit_words}")
    return data, words


def configure_tty(fd, baud):
    if baud not in BAUDS:
        raise SystemExit(f"Unsupported baud {baud}")
    old = termios.tcgetattr(fd)
    new = termios.tcgetattr(fd)
    new[0] = 0
    new[1] = 0
    new[2] = termios.CS8 | termios.CREAD | termios.CLOCAL
    new[3] = 0
    new[4] = BAUDS[baud]
    new[5] = BAUDS[baud]
    new[6][termios.VMIN] = 0
    new[6][termios.VTIME] = 0
    termios.tcsetattr(fd, termios.TCSANOW, new)
    termios.tcflush(fd, termios.TCIOFLUSH)
    return old


def write_all(fd, data):
    view = memoryview(data)
    while view:
        _, writable, _ = select.select([], [fd], [], 5)
        if not writable:
            raise TimeoutError("serial write timed out")
        n = os.write(fd, view[:4096])
        view = view[n:]
    termios.tcdrain(fd)


def read_ack(fd, timeout, echo_ignored=False):
    deadline = time.monotonic() + timeout
    ignored = bytearray()
    while time.monotonic() < deadline:
        readable, _, _ = select.select([fd], [], [], 0.1)
        if not readable:
            continue
        data = os.read(fd, 1024)
        for index, byte in enumerate(data):
            if byte == ord("K"):
                if echo_ignored and ignored:
                    sys.stdout.buffer.write(ignored)
                    sys.stdout.buffer.flush()
                return data[index + 1 :]
            if byte == ord("E"):
                raise RuntimeError("loader returned error")
            ignored.append(byte)
    if ignored and echo_ignored:
        sys.stdout.buffer.write(ignored)
        sys.stdout.buffer.flush()
    raise TimeoutError("loader ACK timed out")


def command(fd, code, payload=b"", timeout=5):
    write_all(fd, code + payload)
    return read_ack(fd, timeout)


def load_region(fd, code, name, data, words, baud, timeout_scale):
    payload = struct.pack("<I", words) + data
    timeout = max(5, len(payload) * 12 / baud + 5) * timeout_scale
    print(f"load {name}: {len(data)} bytes ({words} words)")
    command(fd, code, payload, timeout)


def monitor(fd, seconds):
    if seconds <= 0:
        return
    print(f"monitoring for {seconds:g}s")
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        readable, _, _ = select.select([fd], [], [], 0.1)
        if readable:
            data = os.read(fd, 4096)
            if data:
                sys.stdout.buffer.write(data)
                sys.stdout.buffer.flush()


def main():
    parser = argparse.ArgumentParser(description="Load IMEM/DMEM through the BoardTop UART loader")
    parser.add_argument("--device", default=os.environ.get("LOADER_TTY", "/dev/ttyUSB0"))
    parser.add_argument("--baud", type=int, default=115200)
    parser.add_argument("--text", default="sim/programs/freertos/build/smoke.text.bin")
    parser.add_argument("--data", default="sim/programs/freertos/build/smoke.data.bin")
    parser.add_argument("--no-clear", action="store_true")
    parser.add_argument("--monitor", type=float, default=5.0)
    parser.add_argument("--timeout-scale", type=float, default=1.0)
    args = parser.parse_args()

    text_data, text_words = padded_words(args.text, IMEM_WORDS)
    data_data, data_words = padded_words(args.data, DMEM_WORDS)

    fd = os.open(args.device, os.O_RDWR | os.O_NOCTTY | os.O_SYNC)
    old_attrs = configure_tty(fd, args.baud)
    try:
        print("reset loader")
        command(fd, b"R", timeout=5)
        if not args.no_clear:
            print("clear memories")
            command(fd, b"C", timeout=5)
        load_region(fd, b"I", "IMEM", text_data, text_words, args.baud, args.timeout_scale)
        load_region(fd, b"D", "DMEM", data_data, data_words, args.baud, args.timeout_scale)
        print("boot")
        trailing = command(fd, b"B", timeout=5)
        if trailing:
            sys.stdout.buffer.write(trailing)
            sys.stdout.buffer.flush()
        monitor(fd, args.monitor)
    finally:
        termios.tcsetattr(fd, termios.TCSANOW, old_attrs)
        os.close(fd)


if __name__ == "__main__":
    main()

"""
BS MAGIC KEYBOARD — serveur PC (mode Wi-Fi)
Reçoit les commandes du téléphone et pilote la souris et le clavier de l'ordinateur.
© BSINALY – 0779687995
"""
import json
import random
import socket
import threading

from pynput.keyboard import Controller as KeyboardController, Key
from pynput.mouse import Button, Controller as MouseController

PORT = 5577
DISCOVERY_PORT = 5578
PIN = f"{random.randint(0, 9999):04d}"

mouse = MouseController()
kb = KeyboardController()


def _k(name):
    return getattr(Key, name, None)


NAMED = {
    "enter": _k("enter"), "esc": _k("esc"), "backspace": _k("backspace"),
    "tab": _k("tab"), "space": _k("space"), "capslock": _k("caps_lock"),
    "printscreen": _k("print_screen"), "insert": _k("insert"), "home": _k("home"),
    "pgup": _k("page_up"), "delete": _k("delete"), "end": _k("end"),
    "pgdn": _k("page_down"), "right": _k("right"), "left": _k("left"),
    "down": _k("down"), "up": _k("up"), "menu": _k("menu"), "win": _k("cmd"),
    "num0": "0",
}
for i in range(1, 13):
    NAMED[f"f{i}"] = _k(f"f{i}")

MODS = {"ctrl": _k("ctrl"), "shift": _k("shift"), "alt": _k("alt"),
        "win": _k("cmd"), "altgr": _k("alt_gr")}

MEDIA = {"vol_up": "media_volume_up", "vol_down": "media_volume_down",
         "mute": "media_volume_mute", "play": "media_play_pause",
         "next": "media_next", "prev": "media_previous"}

BUTTONS = {"left": Button.left, "right": Button.right, "middle": Button.middle}


def local_ips():
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127."):
                ips.add(ip)
    except OSError:
        pass
    return sorted(ips) or ["(adresse inconnue)"]


def tap(key, mods):
    held = [MODS[m] for m in mods if MODS.get(m)]
    for m in held:
        kb.press(m)
    try:
        if key is not None:
            kb.press(key)
            kb.release(key)
    finally:
        for m in reversed(held):
            kb.release(m)


def handle(cmd):
    t = cmd.get("t")
    if t == "move":
        mouse.move(int(cmd.get("x", 0)), int(cmd.get("y", 0)))
    elif t == "btn":
        b = BUTTONS.get(cmd.get("b"))
        if b:
            (mouse.press if cmd.get("d") else mouse.release)(b)
    elif t == "scroll":
        mouse.scroll(0, int(cmd.get("v", 0)))
    elif t == "text":
        kb.type(cmd.get("s", ""))
    elif t == "key":
        name = cmd.get("k", "")
        mods = cmd.get("m", [])
        if name == "win" and not mods:
            tap(_k("cmd"), [])
            return
        key = NAMED.get(name)
        if key is None and len(name) == 1:
            key = name
        if key is not None:
            tap(key, mods)
    elif t == "media":
        k = _k(MEDIA.get(cmd.get("k"), ""))
        if k is not None:
            tap(k, [])


def client(conn, addr):
    who = addr[0]
    try:
        f = conn.makefile("r", encoding="utf-8")
        hello = json.loads(f.readline() or "{}")
        if hello.get("t") != "hello" or str(hello.get("pin")) != PIN:
            conn.sendall(b"BADPIN\n")
            print(f"  ✗ {who} : code incorrect")
            return
        conn.sendall(b"OK\n")
        print(f"  ✓ Téléphone connecté : {who}")
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                handle(json.loads(line))
            except Exception as e:  # une commande ratée ne doit pas couper la session
                print(f"    (commande ignorée : {e})")
    except Exception:
        pass
    finally:
        try:
            conn.close()
        except OSError:
            pass
        print(f"  ○ Téléphone déconnecté : {who}")


def discovery():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("", DISCOVERY_PORT))
    name = socket.gethostname()
    while True:
        try:
            data, addr = s.recvfrom(256)
            if data.startswith(b"BSMK?"):
                s.sendto(f"BSMK!|{name}|{PORT}".encode("utf-8"), addr)
        except OSError:
            pass


def main():
    print("=" * 56)
    print("   BS MAGIC KEYBOARD — Serveur PC (mode Wi-Fi)")
    print("   © BSINALY – 0779687995")
    print("=" * 56)
    print(f"   Nom du PC      : {socket.gethostname()}")
    print(f"   Adresse(s) IP  : {', '.join(local_ips())}")
    print(f"   CODE À SAISIR  : {PIN}")
    print("=" * 56)
    print(" Dans l'appli : Connexion › Wi-Fi › Rechercher les ordinateurs")
    print(" Laissez cette fenêtre ouverte. Fermez-la pour arrêter.\n")
    threading.Thread(target=discovery, daemon=True).start()
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("", PORT))
    srv.listen(4)
    while True:
        conn, addr = srv.accept()
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        threading.Thread(target=client, args=(conn, addr), daemon=True).start()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
    except OSError as e:
        print(f"Erreur réseau : {e}\n(Le programme est peut-être déjà ouvert.)")
        input("Appuyez sur Entrée pour fermer…")

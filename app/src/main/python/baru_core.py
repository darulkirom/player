"""
(modul: baru_core, nama unik supaya tidak bentrok dengan paket lain)
Port dari baru.sh: ambil info video lewat yt-dlp lalu susun daftar pilihan
yang bisa langsung diputar di ExoPlayer (tanpa ffmpeg, tanpa proxy localhost).
"""
import collections
import json
import os
import re
import traceback

import yt_dlp

NONE = (None, "none")
ANSI = re.compile(r"\x1b\[[0-9;]*m")
SKIP_HEADERS = ("host", "content-length", "accept-encoding")


# ------------------------------------------------------------------ util

def _site(url):
    m = re.match(r"https?://([^/]+)", url.strip())
    h = (m.group(1) if m else url).lower()
    if "instagram" in h:
        return "instagram"
    if "tiktok" in h:
        return "tiktok"
    if "facebook" in h or "fb.watch" in h or "fb.com" in h:
        return "facebook"
    if "youtube" in h or "youtu.be" in h:
        return "youtube"
    if "twitter" in h or re.search(r"(^|\.)x\.com$", h):
        return "twitter"
    if "twitch" in h:
        return "twitch"
    return "lain"


def _proto(f):
    return f.get("protocol") or ""


def _hkey(f):
    return (f.get("height") or 0, f.get("tbr") or 0)


def _hdrs(f):
    return {
        k: str(v)
        for k, v in (f.get("http_headers") or {}).items()
        if k.lower() not in SKIP_HEADERS
    }


def _clean_err(e):
    lines = [l for l in ANSI.sub("", str(e)).splitlines() if l.strip()]
    return "\n".join(lines[-2:])


def _nama_res(f):
    # angka resolusi = sisi terpendek (video vertikal 720x1280 = 720p)
    h, w = f.get("height"), f.get("width")
    if h and w:
        return min(h, w)
    if h:
        return h
    # sd/hd Facebook tidak punya info resolusi: baca dari parameter "tag" di URL
    try:
        import urllib.parse
        q = urllib.parse.parse_qs(urllib.parse.urlparse(f.get("url") or "").query)
        m = re.search(r"(\d{3,4})p", (q.get("tag") or [""])[0])
        if m:
            return int(m.group(1))
    except Exception:
        pass
    return None


def _opt(kind, label, **kw):
    o = {"kind": kind, "label": label}
    o.update(kw)
    return o


# ------------------------------------------------------------- ekstraksi

def _extract(url, cookie_file, js_path):
    opts = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "skip_download": True,
    }
    if cookie_file:
        opts["cookiefile"] = cookie_file
    if js_path:
        # runtime JS untuk tantangan YouTube (QuickJS), kalau binary-nya disertakan
        opts["js_runtimes"] = {"quickjs": {"path": js_path}}
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)
        return ydl.sanitize_info(info)


def _context(d):
    fs = d.get("formats") or []

    hls = [f for f in fs if "m3u8" in _proto(f)]
    aud = [f for f in hls if f.get("vcodec") in NONE]
    vid = [f for f in hls if f.get("vcodec") not in NONE and f.get("acodec") in NONE]

    # progressive video+audio
    comb = [
        f for f in fs
        if f.get("url")
        and f.get("vcodec") != "none"
        and f.get("acodec") != "none"
        and "m3u8" not in _proto(f)
        and "dash" not in _proto(f)
    ]
    comb.sort(key=_hkey)
    comb = [
        f for f in comb
        if f.get("format_id") != "download"
        and "watermark" not in str(f.get("format_note") or "").lower()
    ]
    seen, uniq = set(), []
    for f in comb:
        if f.get("height") and f.get("tbr"):
            k = (f["height"], f.get("vcodec"), round(f["tbr"] / 10))
        else:
            k = (f.get("format_id"),)
        if k not in seen:
            seen.add(k)
            uniq.append(f)
    comb = uniq

    # HLS yang sudah menyatu
    hlsm = [
        f for f in hls
        if f.get("url")
        and f.get("vcodec") != "none"
        and f.get("acodec") != "none"
    ]
    hlsm.sort(key=_hkey)
    seen, uniq = set(), []
    for f in hlsm:
        if f.get("height") and f.get("tbr"):
            k = (f["height"], round(f["tbr"] / 10))
        else:
            k = (f.get("format_id"),)
        if k not in seen:
            seen.add(k)
            uniq.append(f)
    hlsm = uniq

    mpd = [f for f in fs if "dash" in _proto(f) and f.get("manifest_url")]

    vo = [
        f for f in fs
        if f.get("vcodec") not in NONE
        and f.get("acodec") in NONE
        and "m3u8" not in _proto(f)
    ]
    ao = [
        f for f in fs
        if f.get("vcodec") in NONE
        and f.get("acodec") not in NONE
        and "m3u8" not in _proto(f)
    ]
    return dict(d=d, hls=hls, aud=aud, vid=vid, comb=comb, hlsm=hlsm,
                mpd=mpd, vo=vo, ao=ao)


# ------------------------------------------------------------ strategi

def _hls_split(c):
    aud, vid = c["aud"], c["vid"]
    if not (aud and vid):
        return []
    a = max(aud, key=lambda f: f.get("tbr") or f.get("abr") or 0)
    vs = sorted(vid, key=_hkey)
    lines = [
        "#EXTM3U",
        '#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="audio",DEFAULT=YES,'
        'AUTOSELECT=YES,URI="%s"' % a["url"],
    ]
    for v in vs:
        lines.append(
            '#EXT-X-STREAM-INF:BANDWIDTH=%d,RESOLUTION=%sx%s,'
            'CODECS="%s,mp4a.40.2",AUDIO="aud"'
            % (int((v.get("tbr") or 1000) * 1000),
               v.get("width") or 0, v.get("height") or 0,
               v.get("vcodec") or "")
        )
        lines.append(v["url"])
    names = ", ".join("%sp" % (v.get("height") or "?") for v in vs)
    headers = dict(_hdrs(a))
    headers.update(_hdrs(vs[-1]))
    return [_opt("hls_split", "Adaptif HLS (video+audio terpisah): %s" % names,
                 master_text="\n".join(lines) + "\n", headers=headers)]


def _master(c):
    ms = [(f["manifest_url"], f) for f in c["hls"] if f.get("manifest_url")]
    if not ms:
        return []
    url = collections.Counter(u for u, _ in ms).most_common(1)[0][0]
    f = next(f for u, f in ms if u == url)
    return [_opt("hls_master", "HLS master (adaptif, kualitas dipilih di player)",
                 url=url, headers=_hdrs(f))]


def _dash(c):
    if not c["mpd"]:
        return []
    url = collections.Counter(f["manifest_url"] for f in c["mpd"]).most_common(1)[0][0]
    f = next(f for f in c["mpd"] if f["manifest_url"] == url)
    return [_opt("dash", "DASH .mpd (kualitas dipilih di player)",
                 url=url, headers=_hdrs(f))]


def _playlist(c):
    entries = []  # (res, prioritas, tbr, opsi)

    for f in c["comb"]:
        n = _nama_res(f)
        lab = ("%sp" % n if n else str(f.get("format_id", "?"))) + " [langsung]"
        entries.append((n, 0, f.get("tbr"),
                        _opt("single", lab, url=f["url"], headers=_hdrs(f))))

    for f in c["hlsm"]:
        n = _nama_res(f)
        lab = ("%sp" % n if n else str(f.get("format_id", "?"))) + " [HLS]"
        entries.append((n, 1, f.get("tbr"),
                        _opt("hls", lab, url=f["url"], headers=_hdrs(f))))

    vo, ao = c["vo"], c["ao"]
    if vo and ao:
        au = max(ao, key=lambda f: f.get("abr") or f.get("tbr") or 0)
        if au.get("url"):
            seen = set()
            for f in vo:
                if not f.get("url") or "dash" in _proto(f):
                    continue  # segmen DASH tidak bisa diputar sebagai file; lihat opsi .mpd
                key = (f.get("width") or 0, f.get("height") or 0,
                       f.get("vcodec"), round((f.get("tbr") or 0) / 10))
                if key in seen:
                    continue
                seen.add(key)
                n = _nama_res(f)
                codec = str(f.get("vcodec") or "?").split(".")[0]
                lab = "%sp %s [gabung, %dk]" % (n if n else "?", codec,
                                                 int(f.get("tbr") or 0))
                entries.append((n, 2, f.get("tbr"), _opt(
                    "merge", lab,
                    url=f["url"], headers=_hdrs(f),
                    audio_url=au["url"], audio_headers=_hdrs(au))))

    entries.sort(key=lambda e: (-(e[0] or 0), e[1], -(e[2] or 0)))
    return [e[3] for e in entries]


def _single(c):
    comb, d = c["comb"], c["d"]
    u = comb[-1]["url"] if comb else d.get("url")
    if not u:
        return []
    h = _hdrs(comb[-1]) if comb else {}
    return [_opt("single", "Link langsung", url=u, headers=h)]


# Urutan prioritas per platform (sama seperti `urutan` di baru.sh).
# "format" = playlist per kualitas + DASH.
ORDER = {
    "1": ["hls_split", "master", "format"],
    "2": ["format", "master", "hls_split"],
    "3": ["master", "hls_split", "format"],
    "4": ["format", "master", "hls_split"],
}
DEFAULT_ORDER = ["hls_split", "master", "format"]


def _build(c, platform):
    out = []
    for name in ORDER.get(str(platform), DEFAULT_ORDER):
        if name == "hls_split":
            out += _hls_split(c)
        elif name == "master":
            out += _master(c)
        else:
            out += _playlist(c) + _dash(c)
    if not out:
        out = _single(c)
    return out


# ---------------------------------------------------------- API untuk Java

def analyze(url, platform, cookie_dir, legacy_dir, js_path):
    """Return JSON string: {ok, title, options[]} atau {ok:false, need_cookie, site, error}."""
    try:
        return _analyze(url, platform, cookie_dir, legacy_dir, js_path)
    except BaseException:
        # jangan lempar exception mentah ke Java; kirim traceback sebagai teks
        return json.dumps({"ok": False, "need_cookie": False, "site": "",
                           "error": traceback.format_exc()[-1800:]})


def _analyze(url, platform, cookie_dir, legacy_dir, js_path):
    url = (url or "").strip()
    site = _site(url)
    cf = _cookie_path(cookie_dir, legacy_dir, site)

    d, err = None, ""

    # 1. tanpa cookie
    try:
        d = _extract(url, None, js_path)
    except Exception as e:
        err = _clean_err(e)

    # 2. dengan cookie tersimpan
    if d is None and os.path.isfile(cf) and os.path.getsize(cf) > 0:
        try:
            d = _extract(url, cf, js_path)
        except Exception as e:
            err = _clean_err(e)

    # 3. minta cookie baru dari pengguna
    if d is None:
        return json.dumps({"ok": False, "need_cookie": True,
                           "site": site, "error": err})

    if d.get("_type") == "playlist" and d.get("entries"):
        d = next((e for e in d["entries"] if e), d)

    options = _build(_context(d), platform)
    if not options:
        return json.dumps({
            "ok": False, "need_cookie": False, "site": site,
            "error": "Tidak ada link yang cocok untuk platform ini. "
                     "Coba pilih \"Lainnya\"."})

    return json.dumps({
        "ok": True,
        "title": d.get("title") or str(d.get("id") or "video"),
        "ytdlp": getattr(yt_dlp.version, "__version__", "?"),
        "options": options,
    })


def _cookie_path(cookie_dir, legacy_dir, site):
    """Path cookie untuk situs; salin dari folder lama (internal) kalau baru pindah lokasi."""
    os.makedirs(cookie_dir, exist_ok=True)
    cf = os.path.join(cookie_dir, site + ".txt")
    old = os.path.join(legacy_dir or "", site + ".txt")
    if (not os.path.isfile(cf)) and legacy_dir and os.path.isfile(old) \
            and os.path.abspath(old) != os.path.abspath(cf):
        try:
            with open(old, "rb") as src, open(cf, "wb") as dst:
                dst.write(src.read())
        except OSError:
            pass
    return cf


def save_cookie(cookie_dir, site, text):
    """Simpan cookies.txt (format Netscape) untuk situs tertentu."""
    text = (text or "").lstrip("\ufeff \r\n\t")
    first = text.splitlines()[0] if text else ""
    if "cookie file" not in first.lower():
        return json.dumps({
            "ok": False,
            "error": "Isinya bukan cookies.txt. Baris pertama harus: "
                     "# Netscape HTTP Cookie File"})
    os.makedirs(cookie_dir, exist_ok=True)
    path = os.path.join(cookie_dir, site + ".txt")
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text.replace("\r\n", "\n").rstrip("\n") + "\n")
    try:
        os.chmod(path, 0o600)
    except Exception:
        pass
    return json.dumps({"ok": True})

"""Normalised dexdump -d text per class: offsets, raw bytes, class numbers and index numbers removed."""
import re

def classes(path):
    out, name, buf = {}, None, []
    for line in open(path, encoding="utf-8", errors="replace"):
        m = re.match(r"\s*Class descriptor\s*: '([^']+)'", line)
        if m:
            if name:
                out[name] = "\n".join(buf)
            name, buf = m.group(1), []
            continue
        if line.startswith("Class #"):
            continue
        line = re.sub(r"^[0-9a-f]{6}:[^|]*\|[0-9a-f]{4}: ", "", line)       # code lines: offset and raw bytes
        line = re.sub(r"^[0-9a-f]{6}:\s+\|\[[0-9a-f]{6}\]", "[method]", line)
        line = re.sub(r"// (string|method|field|type|call_site|proto)@[0-9a-f]+", "", line)
        line = re.sub(r"(string|method|field|type|call_site|proto)@[0-9a-f]+", r"\1@", line)
        line = re.sub(r"0x[0-9a-f]+ line=\d+", "line", line)
        line = re.sub(r"(code_off|source_file_idx|class_idx|superclass_idx|access_flags)\s*: .*", r"\1", line)
        line = re.sub(r"\$polarka_wallet_app_(public|personal)Release", "$MODULE", line)   # internal names carry the edition
        buf.append(line.rstrip())
    if name:
        out[name] = "\n".join(buf)
    return out

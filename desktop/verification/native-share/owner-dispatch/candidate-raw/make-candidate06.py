from pathlib import Path
import hashlib
HERE=Path(__file__).resolve().parent;original=HERE/'candidate05/DesktopDiagnosticShare.cpp'
assert hashlib.sha256(original.read_bytes()).hexdigest()=='93cd216e5e47db41b7db45f550d735f76c32f19e836bce92641413cfd606b90b'
source=original.read_text(encoding='utf-8')
source=source.replace('    HRESULT revokePackage() noexcept {','    bool beginStorageSupply() noexcept {\n        if(closed)return false;\n        dataSupplied=true;return true;\n    }\n    HRESULT revokePackage() noexcept {',1)
assert source.count('                value->dataSupplied=true;')==1
source=source.replace('                value->dataSupplied=true;','                // All preparatory COM calls have finished. Closed authority may\n                // not start a new storage attempt; no COM call separates this\n                // admission check from its conservative possible-supply flag.\n                if(!value->beginStorageSupply())return;')
out=HERE/'candidate06/DesktopDiagnosticShare.cpp';out.parent.mkdir(exist_ok=True);assert not out.exists()
out.write_text(source,encoding='utf-8',newline='\n');print(hashlib.sha256(out.read_bytes()).hexdigest())

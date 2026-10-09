#!/usr/bin/env python3
"""Recipe-owned exact curl/libssh SCP public-header compatibility and source witnesses.

No networking, compiler, cryptography or protocol runtime is called here.
Installation observations are taken only after each actual recipe install command.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess

CURL_COMMIT = '098d3a0d4044d8a3f0a8617a5a5a30cde90fba26'
CURL_TREE = '7fa155649a35598bc9952c59e0a9964f7b4f9590'
LIBSSH_COMMIT = '7b3ba877209ae2355f12f4a7ab56022337caaaf5'
LIBSSH_TREE = '965f5cee2228e3d65ed66e6777741babbd286dab'
TARGET = 'lib/vssh/ssh.h'
BEFORE = base64.b64decode('I2lmbmRlZiBIRUFERVJfQ1VSTF9WU1NIX1NTSF9ICiNkZWZpbmUgSEVBREVSX0NVUkxfVlNTSF9TU0hfSAovKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqCiAqICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgIF8gICBfIF9fX18gIF8KICogIFByb2plY3QgICAgICAgICAgICAgICAgICAgICBfX198IHwgfCB8ICBfIFx8IHwKICogICAgICAgICAgICAgICAgICAgICAgICAgICAgIC8gX198IHwgfCB8IHxfKSB8IHwKICogICAgICAgICAgICAgICAgICAgICAgICAgICAgfCAoX198IHxffCB8ICBfIDx8IHxfX18KICogICAgICAgICAgICAgICAgICAgICAgICAgICAgIFxfX198XF9fXy98X3wgXF9cX19fX198CiAqCiAqIENvcHlyaWdodCAoQykgRGFuaWVsIFN0ZW5iZXJnLCA8ZGFuaWVsQGhheHguc2U+LCBldCBhbC4KICoKICogVGhpcyBzb2Z0d2FyZSBpcyBsaWNlbnNlZCBhcyBkZXNjcmliZWQgaW4gdGhlIGZpbGUgQ09QWUlORywgd2hpY2gKICogeW91IHNob3VsZCBoYXZlIHJlY2VpdmVkIGFzIHBhcnQgb2YgdGhpcyBkaXN0cmlidXRpb24uIFRoZSB0ZXJtcwogKiBhcmUgYWxzbyBhdmFpbGFibGUgYXQgaHR0cHM6Ly9jdXJsLnNlL2RvY3MvY29weXJpZ2h0Lmh0bWwuCiAqCiAqIFlvdSBtYXkgb3B0IHRvIHVzZSwgY29weSwgbW9kaWZ5LCBtZXJnZSwgcHVibGlzaCwgZGlzdHJpYnV0ZSBhbmQvb3Igc2VsbAogKiBjb3BpZXMgb2YgdGhlIFNvZnR3YXJlLCBhbmQgcGVybWl0IHBlcnNvbnMgdG8gd2hvbSB0aGUgU29mdHdhcmUgaXMKICogZnVybmlzaGVkIHRvIGRvIHNvLCB1bmRlciB0aGUgdGVybXMgb2YgdGhlIENPUFlJTkcgZmlsZS4KICoKICogVGhpcyBzb2Z0d2FyZSBpcyBkaXN0cmlidXRlZCBvbiBhbiAiQVMgSVMiIGJhc2lzLCBXSVRIT1VUIFdBUlJBTlRZIE9GIEFOWQogKiBLSU5ELCBlaXRoZXIgZXhwcmVzcyBvciBpbXBsaWVkLgogKgogKiBTUERYLUxpY2Vuc2UtSWRlbnRpZmllcjogY3VybAogKgogKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqLwojaW5jbHVkZSAiY3VybF9zZXR1cC5oIgojaW5jbHVkZSAidXJsZGF0YS5oIgoKZXh0ZXJuIGNvbnN0IHN0cnVjdCBDdXJsX3Byb3RvY29sIEN1cmxfcHJvdG9jb2xfc2Z0cDsKZXh0ZXJuIGNvbnN0IHN0cnVjdCBDdXJsX3Byb3RvY29sIEN1cmxfcHJvdG9jb2xfc2NwOwoKI2lmZGVmIFVTRV9TU0gKCiNpZmRlZiBVU0VfTElCU1NIMgojaW5jbHVkZSA8bGlic3NoMi5oPgojaW5jbHVkZSA8bGlic3NoMl9zZnRwLmg+CiNlbGlmIGRlZmluZWQoVVNFX0xJQlNTSCkKLyogaW4gMC4xMC4wIG9yIGxhdGVyLCBpZ25vcmUgZGVwcmVjYXRlZCB3YXJuaW5ncyAqLwojZGVmaW5lIFNTSF9TVVBQUkVTU19ERVBSRUNBVEVECiNpbmNsdWRlIDxsaWJzc2gvbGlic3NoLmg+CiNpbmNsdWRlIDxsaWJzc2gvc2Z0cC5oPgojZW5kaWYKCi8qIG1ldGEga2V5IGZvciBzdG9yaW5nIHByb3RvY29sIG1ldGEgYXQgZWFzeSBoYW5kbGUgKi8KI2RlZmluZSBDVVJMX01FVEFfU1NIX0VBU1kgICAibWV0YTpwcm90bzpzc2g6ZWFzeSIKLyogbWV0YSBrZXkgZm9yIHN0b3JpbmcgcHJvdG9jb2wgbWV0YSBhdCBjb25uZWN0aW9uICovCiNkZWZpbmUgQ1VSTF9NRVRBX1NTSF9DT05OICAgIm1ldGE6cHJvdG86c3NoOmNvbm4iCgovKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKgogKiBTU0ggdW5pcXVlIHNldHVwCiAqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKioqKiovCnR5cGVkZWYgZW51bSB7CiAgU1NIX05PX1NUQVRFID0gLTEsICAvKiBVc2VkIGZvciAibmV4dFN0YXRlIiBzbyBzYXkgdGhlcmUgaXMgbm9uZSAqLwogIFNTSF9TVE9QID0gMCwgICAgICAgLyogZG8gbm90aGluZyBzdGF0ZSwgc3RvcHMgdGhlIHN0YXRlIG1hY2hpbmUgKi8KCiAgU1NIX0lOSVQsICAgICAgICAgICAvKiBGaXJzdCBzdGF0ZSBpbiBTU0gtQ09OTkVDVCAqLwogIFNTSF9TX1NUQVJUVVAsICAgICAgLyogU2Vzc2lvbiBzdGFydHVwICovCiAgU1NIX0hPU1RLRVksICAgICAgICAvKiB2ZXJpZnkgaG9zdGtleSAqLwogIFNTSF9BVVRITElTVCwKICBTU0hfQVVUSF9QS0VZX0lOSVQsCiAgU1NIX0FVVEhfUEtFWSwKICBTU0hfQVVUSF9QQVNTX0lOSVQsCiAgU1NIX0FVVEhfUEFTUywKICBTU0hfQVVUSF9BR0VOVF9JTklULCAvKiBpbml0aWFsaXplIHRoZW4gd2FpdCBmb3IgY29ubmVjdGlvbiB0byBhZ2VudCAqLwogIFNTSF9BVVRIX0FHRU5UX0xJU1QsIC8qIGFzayBmb3IgbGlzdCB0aGVuIHdhaXQgZm9yIGVudGlyZSBsaXN0IHRvIGNvbWUgKi8KICBTU0hfQVVUSF9BR0VOVCwgICAgICAvKiBhdHRlbXB0IG9uZSBrZXkgYXQgYSB0aW1lICovCiAgU1NIX0FVVEhfSE9TVF9JTklULAogIFNTSF9BVVRIX0hPU1QsCiAgU1NIX0FVVEhfS0VZX0lOSVQsCiAgU1NIX0FVVEhfS0VZLAogIFNTSF9BVVRIX0dTU0FQSSwKICBTU0hfQVVUSF9ET05FLAogIFNTSF9TRlRQX0lOSVQsCiAgU1NIX1NGVFBfUkVBTFBBVEgsICAgLyogTGFzdCBzdGF0ZSBpbiBTU0gtQ09OTkVDVCAqLwoKICBTU0hfU0ZUUF9RVU9URV9JTklULCAvKiBGaXJzdCBzdGF0ZSBpbiBTRlRQLURPICovCiAgU1NIX1NGVFBfUE9TVFFVT1RFX0lOSVQsIC8qIChQb3NzaWJseSkgRmlyc3Qgc3RhdGUgaW4gU0ZUUC1ET05FICovCiAgU1NIX1NGVFBfUVVPVEUsCiAgU1NIX1NGVFBfTkVYVF9RVU9URSwKICBTU0hfU0ZUUF9RVU9URV9TVEFULAogIFNTSF9TRlRQX1FVT1RFX1NFVFNUQVQsCiAgU1NIX1NGVFBfUVVPVEVfU1lNTElOSywKICBTU0hfU0ZUUF9RVU9URV9NS0RJUiwKICBTU0hfU0ZUUF9RVU9URV9SRU5BTUUsCiAgU1NIX1NGVFBfUVVPVEVfUk1ESVIsCiAgU1NIX1NGVFBfUVVPVEVfVU5MSU5LLAogIFNTSF9TRlRQX1FVT1RFX1NUQVRWRlMsCiAgU1NIX1NGVFBfR0VUSU5GTywKICBTU0hfU0ZUUF9GSUxFVElNRSwKICBTU0hfU0ZUUF9UUkFOU19JTklULAogIFNTSF9TRlRQX1VQTE9BRF9JTklULAogIFNTSF9TRlRQX0NSRUFURV9ESVJTX0lOSVQsCiAgU1NIX1NGVFBfQ1JFQVRFX0RJUlMsCiAgU1NIX1NGVFBfQ1JFQVRFX0RJUlNfTUtESVIsCiAgU1NIX1NGVFBfUkVBRERJUl9JTklULAogIFNTSF9TRlRQX1JFQURESVIsCiAgU1NIX1NGVFBfUkVBRERJUl9MSU5LLAogIFNTSF9TRlRQX1JFQURESVJfQk9UVE9NLAogIFNTSF9TRlRQX1JFQURESVJfRE9ORSwKICBTU0hfU0ZUUF9ET1dOTE9BRF9JTklULAogIFNTSF9TRlRQX0RPV05MT0FEX1NUQVQsIC8qIExhc3Qgc3RhdGUgaW4gU0ZUUC1ETyAqLwogIFNTSF9TRlRQX0NMT1NFLCAgICAvKiBMYXN0IHN0YXRlIGluIFNGVFAtRE9ORSAqLwogIFNTSF9TRlRQX1NIVVRET1dOLCAvKiBGaXJzdCBzdGF0ZSBpbiBTRlRQLURJU0NPTk5FQ1QgKi8KICBTU0hfU0NQX1RSQU5TX0lOSVQsIC8qIEZpcnN0IHN0YXRlIGluIFNDUC1ETyAqLwogIFNTSF9TQ1BfVVBMT0FEX0lOSVQsCiAgU1NIX1NDUF9ET1dOTE9BRF9JTklULAogIFNTSF9TQ1BfRE9XTkxPQUQsCiAgU1NIX1NDUF9ET05FLAogIFNTSF9TQ1BfU0VORF9FT0YsCiAgU1NIX1NDUF9XQUlUX0VPRiwKICBTU0hfU0NQX1dBSVRfQ0xPU0UsCiAgU1NIX1NDUF9DSEFOTkVMX0ZSRUUsICAgLyogTGFzdCBzdGF0ZSBpbiBTQ1AtRE9ORSAqLwogIFNTSF9TRVNTSU9OX0RJU0NPTk5FQ1QsIC8qIEZpcnN0IHN0YXRlIGluIFNDUC1ESVNDT05ORUNUICovCiAgU1NIX1NFU1NJT05fRlJFRSwgICAgICAgLyogTGFzdCBzdGF0ZSBpbiBTQ1AvU0ZUUC1ESVNDT05ORUNUICovCiAgU1NIX1FVSVQsCiAgU1NIX0xBU1QgIC8qIG5ldmVyIHVzZWQgKi8KfSBzc2hzdGF0ZTsKCiNkZWZpbmUgQ1VSTF9QQVRIX01BWCAxMDI0CgovKiB0aGlzIHN0cnVjdCBpcyB1c2VkIGluIHRoZSBIYW5kbGVEYXRhIHN0cnVjdCB3aGljaCBpcyBwYXJ0IG9mIHRoZQogICBDdXJsX2Vhc3ksIHdoaWNoIG1lYW5zIHRoaXMgaXMgdXNlZCBvbiBhIHBlci1lYXN5IGhhbmRsZSBiYXNpcy4KICAgRXZlcnl0aGluZyB0aGF0IGlzIHN0cmljdGx5IHJlbGF0ZWQgdG8gYSBjb25uZWN0aW9uIGlzIGJhbm5lZCBmcm9tIHRoaXMKICAgc3RydWN0LiAqLwpzdHJ1Y3QgU1NIUFJPVE8gewogIGNoYXIgKnBhdGg7ICAgICAgICAvKiB0aGUgcGF0aCB3ZSBvcGVyYXRlIG9uLCBhdCBsZWFzdCBvbmUgYnl0ZSBsb25nICovCiNpZmRlZiBVU0VfTElCU1NIMgogIHN0cnVjdCBkeW5idWYgcmVhZGRpcl9saW5rOwogIHN0cnVjdCBkeW5idWYgcmVhZGRpcjsKICBjaGFyIHJlYWRkaXJfZmlsZW5hbWVbQ1VSTF9QQVRIX01BWCArIDFdOwogIGNoYXIgcmVhZGRpcl9sb25nZW50cnlbQ1VSTF9QQVRIX01BWCArIDFdOwoKICBMSUJTU0gyX1NGVFBfQVRUUklCVVRFUyBxdW90ZV9hdHRyczsgLyogdXNlZCBieSB0aGUgU0ZUUF9RVU9URSBzdGF0ZSAqLwoKICAvKiBIZXJlJ3MgYSBzZXQgb2Ygc3RydWN0IG1lbWJlcnMgdXNlZCBieSB0aGUgU0ZUUF9SRUFERElSIHN0YXRlICovCiAgTElCU1NIMl9TRlRQX0FUVFJJQlVURVMgcmVhZGRpcl9hdHRyczsKI2VuZGlmCn07CgovKiBzc2hfY29ubiBpcyB1c2VkIGZvciBzdHJ1Y3QgY29ubmVjdGlvbi1vcmllbnRlZCBkYXRhIGluIHRoZSBjb25uZWN0ZGF0YQogICBzdHJ1Y3QgKi8Kc3RydWN0IHNzaF9jb25uIHsKICBjb25zdCBjaGFyICphdXRobGlzdDsgICAgICAgLyogTGlzdCBvZiBhdXRoLiBtZXRob2RzLCBtYW5hZ2VkIGJ5IGxpYnNzaDIgKi8KCiAgLyogY29tbW9uICovCiAgY2hhciAqcGFzc3BocmFzZTsgICAgICAgICAgIC8qIHN0cmR1cCdlZCBwYXNzLXBocmFzZSB0byB1c2Ugb3IgTlVMTCAqLwogIGNoYXIgKnB1Yl9rZXk7ICAgICAgICAgICAgICAvKiBzdHJkdXAnZWQgcHVibGljIGtleSBmaWxlICovCiAgY2hhciAqcHJpdl9rZXk7ICAgICAgICAgICAgIC8qIHN0cmR1cCdlZCBwcml2YXRlIGtleSBmaWxlICovCiAgc3Noc3RhdGUgc3RhdGU7ICAgICAgICAgICAgIC8qIGFsd2F5cyB1c2Ugc3NoLmM6c3RhdGUoKSB0byBjaGFuZ2Ugc3RhdGUhICovCiAgc3Noc3RhdGUgbmV4dHN0YXRlOyAgICAgICAgIC8qIHRoZSBzdGF0ZSB0byBnb3RvIGFmdGVyIHN0b3BwaW5nICovCiAgc3RydWN0IGN1cmxfc2xpc3QgKnF1b3RlX2l0ZW07IC8qIGZvciB0aGUgcXVvdGUgb3B0aW9uICovCiAgY2hhciAqcXVvdGVfcGF0aDE7ICAgICAgICAgIC8qIHR3byBnZW5lcmljIHBvaW50ZXJzIGZvciB0aGUgUVVPVEUgc3R1ZmYgKi8KICBjaGFyICpxdW90ZV9wYXRoMjsKCiAgY2hhciAqaG9tZWRpcjsgICAgICAgICAgICAgIC8qIHdoZW4gZG9pbmcgU0ZUUCB3ZSBmaWd1cmUgb3V0IGhvbWUgZGlyZWN0b3J5CiAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgIGluIHRoZSBjb25uZWN0IHBoYXNlICovCiAgLyogZW5kIG9mIFJFQURESVIgc3R1ZmYgKi8KCiAgaW50IHNlY29uZENyZWF0ZURpcnM7ICAgICAgICAgLyogY291bnRlciB1c2UgYnkgdGhlIGNvZGUgdG8gc2VlIGlmIHRoZQogICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgIHNlY29uZCBhdHRlbXB0IGhhcyBiZWVuIG1hZGUgdG8gY2hhbmdlCiAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgdG8vY3JlYXRlIGEgZGlyZWN0b3J5ICovCiAgaW50IHdhaXRmb3I7ICAgICAgICAgICAgICAgICAgLyogUkVRX0lPX1JFQ1YvUkVRX0lPX1NFTkQgYml0cyBvdmVycmlkaW5nCiAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgcG9sbHNldCBnaXZlbiBmbGFncyAqLwogIGNoYXIgKnNsYXNoX3BvczsgICAgICAgICAgICAgIC8qIHVzZWQgYnkgdGhlIFNGVFBfQ1JFQVRFX0RJUlMgc3RhdGUgKi8KCiNpZmRlZiBVU0VfTElCU1NICiAgQ1VSTGNvZGUgYWN0dWFsY29kZTsgICAgICAgIC8qIHRoZSBhY3R1YWwgZXJyb3IgY29kZSAqLwogIGNoYXIgKnJlYWRkaXJfbGlua1BhdGg7CiAgc2l6ZV90IHJlYWRkaXJfbGVuOwogIHN0cnVjdCBkeW5idWYgcmVhZGRpcl9idWY7Ci8qIG91ciB2YXJpYWJsZXMgKi8KICB1bnNpZ25lZCBrYmRfc3RhdGU7IC8qIDAgb3IgMSAqLwogIHNzaF9rZXkgcHJpdmtleTsKICBzc2hfa2V5IHB1YmtleTsKICB1bnNpZ25lZCBpbnQgYXV0aF9tZXRob2RzOwogIHNzaF9zZXNzaW9uIHNzaF9zZXNzaW9uOwogIHNzaF9zY3Agc2NwX3Nlc3Npb247CiAgc2Z0cF9zZXNzaW9uIHNmdHBfc2Vzc2lvbjsKICBzZnRwX2ZpbGUgc2Z0cF9maWxlOwogIHNmdHBfZGlyIHNmdHBfZGlyOwoKICB1bnNpZ25lZCBzZnRwX3JlY3Zfc3RhdGU7IC8qIDAgb3IgMSAqLwojaWYgTElCU1NIX1ZFUlNJT05fSU5UID4gU1NIX1ZFUlNJT05fSU5UKDAsIDExLCAwKQogIHNmdHBfYWlvIHNmdHBfcmVjdl9haW87CgogIHNmdHBfYWlvIHNmdHBfc2VuZF9haW87CiAgdW5zaWduZWQgc2Z0cF9zZW5kX3N0YXRlOyAvKiAwIG9yIDEgKi8KI2Vsc2UKICBpbnQgc2Z0cF9maWxlX2luZGV4OyAvKiBmb3IgYXN5bmMgcmVhZCAqLwojZW5kaWYKICBzZnRwX2F0dHJpYnV0ZXMgcmVhZGRpcl9hdHRyczsgLyogdXNlZCBieSB0aGUgU0ZUUCByZWFkZGlyIGFjdGlvbnMgKi8KICBzZnRwX2F0dHJpYnV0ZXMgcmVhZGRpcl9saW5rX2F0dHJzOyAvKiB1c2VkIGJ5IHRoZSBTRlRQIHJlYWRkaXIgYWN0aW9ucyAqLwogIHNmdHBfYXR0cmlidXRlcyBxdW90ZV9hdHRyczsgLyogdXNlZCBieSB0aGUgU0ZUUF9RVU9URSBzdGF0ZSAqLwoKICBjb25zdCBjaGFyICpyZWFkZGlyX2ZpbGVuYW1lOyAvKiBwb2ludHMgd2l0aGluIHJlYWRkaXJfYXR0cnMgKi8KICBjb25zdCBjaGFyICpyZWFkZGlyX2xvbmdlbnRyeTsKICBjaGFyICpyZWFkZGlyX3RtcDsKICBCSVQoaW5pdGlhbGl6ZWQpOwojZWxpZiBkZWZpbmVkKFVTRV9MSUJTU0gyKQogIExJQlNTSDJfU0VTU0lPTiAqc3NoX3Nlc3Npb247IC8qIFNlY3VyZSBTaGVsbCBzZXNzaW9uICovCiAgTElCU1NIMl9DSEFOTkVMICpzc2hfY2hhbm5lbDsgLyogU2VjdXJlIFNoZWxsIGNoYW5uZWwgaGFuZGxlICovCiAgTElCU1NIMl9TRlRQICpzZnRwX3Nlc3Npb247ICAgLyogU0ZUUCBoYW5kbGUgKi8KICBMSUJTU0gyX1NGVFBfSEFORExFICpzZnRwX2hhbmRsZTsKCiNpZm5kZWYgQ1VSTF9ESVNBQkxFX1BST1hZCiAgLyogZm9yIEhUVFBTIHByb3h5IHN0b3JhZ2UgKi8KICBDdXJsX3JlY3YgKnRsc19yZWN2OwogIEN1cmxfc2VuZCAqdGxzX3NlbmQ7CiNlbmRpZgoKICBMSUJTU0gyX0FHRU5UICpzc2hfYWdlbnQ7ICAgICAvKiBwcm94eSB0byBzc2gtYWdlbnQvcGFnZWFudCAqLwogIHN0cnVjdCBsaWJzc2gyX2FnZW50X3B1YmxpY2tleSAqc3NoYWdlbnRfaWRlbnRpdHk7CiAgc3RydWN0IGxpYnNzaDJfYWdlbnRfcHVibGlja2V5ICpzc2hhZ2VudF9wcmV2X2lkZW50aXR5OwogIExJQlNTSDJfS05PV05IT1NUUyAqa2g7CiNlbmRpZiAvKiBVU0VfTElCU1NIICovCiAgQklUKGF1dGhlZCk7ICAgICAgICAgICAgICAgIC8qIHRoZSBjb25uZWN0aW9uIGhhcyBiZWVuIGF1dGhlbnRpY2F0ZWQgZmluZSAqLwogIEJJVChhY2NlcHRmYWlsKTsgICAgICAgICAgICAvKiB1c2VkIGJ5IHRoZSBTRlRQX1FVT1RFIChjb250aW51ZSBpZgogICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICBxdW90ZSBjb21tYW5kIGZhaWxzKSAqLwp9OwoKI2lmZGVmIFVTRV9MSUJTU0gKI2lmIExJQlNTSF9WRVJTSU9OX0lOVCA8IFNTSF9WRVJTSU9OX0lOVCgwLCA5LCAwKQojZXJyb3IgIlNDUC9TRlRQIHByb3RvY29scyByZXF1aXJlIGxpYnNzaCAwLjkuMCBvciBncmVhdGVyIgojZW5kaWYKI2VuZGlmCgojaWZkZWYgVVNFX0xJQlNTSDIKCi8qIEZlYXR1cmUgZGV0ZWN0aW9uIGJhc2VkIG9uIHZlcnNpb24gbnVtYmVycyB0byBiZXR0ZXIgd29yayB3aXRoCiAgIG5vbi1jb25maWd1cmUgcGxhdGZvcm1zICovCgojaWYgIWRlZmluZWQoTElCU1NIMl9WRVJTSU9OX05VTSkgfHwgKExJQlNTSDJfVkVSU0lPTl9OVU0gPCAweDAxMDkwMCkKI2Vycm9yICJTQ1AvU0ZUUCBwcm90b2NvbHMgcmVxdWlyZSBsaWJzc2gyIDEuOS4wIG9yIGdyZWF0ZXIiCi8qIDEuOS4wIHdhcyByZWxlYXNlZCBvbiBKdW5lIDIwIDIwMTkgKi8KI2VuZGlmCgojZW5kaWYgLyogVVNFX0xJQlNTSDIgKi8KCiNpZmRlZiBDVVJMVkVSQk9TRQpjb25zdCBjaGFyICpDdXJsX3NzaF9zdGF0ZW5hbWUoc3Noc3RhdGUgc3RhdGUpOwojZWxzZQojZGVmaW5lIEN1cmxfc3NoX3N0YXRlbmFtZSh4KSAiIgojZW5kaWYKdm9pZCBDdXJsX3NzaF9zZXRfc3RhdGUoc3RydWN0IEN1cmxfZWFzeSAqZGF0YSwKICAgICAgICAgICAgICAgICAgICAgICAgc3RydWN0IHNzaF9jb25uICpzc2hjLAogICAgICAgICAgICAgICAgICAgICAgICBzc2hzdGF0ZSBub3dzdGF0ZSk7CgojZGVmaW5lIG15c3NoX3RvKHgsIHksIHopIEN1cmxfc3NoX3NldF9zdGF0ZSh4LCB5LCB6KQoKLyogZ2VuZXJpYyBTU0ggYmFja2VuZCBmdW5jdGlvbnMgKi8KQ1VSTGNvZGUgQ3VybF9zc2hfaW5pdCh2b2lkKTsKdm9pZCBDdXJsX3NzaF9jbGVhbnVwKHZvaWQpOwp2b2lkIEN1cmxfc3NoX3ZlcnNpb24oY2hhciAqYnVmZmVyLCBzaXplX3QgYnVmbGVuKTsKdm9pZCBDdXJsX3NzaF9hdHRhY2goc3RydWN0IEN1cmxfZWFzeSAqZGF0YSwKICAgICAgICAgICAgICAgICAgICAgc3RydWN0IGNvbm5lY3RkYXRhICpjb25uKTsKI2Vsc2UgLyogIVVTRV9TU0ggKi8KI2RlZmluZSBDdXJsX3NzaF9jbGVhbnVwKCkKI2RlZmluZSBDdXJsX3NzaF9hdHRhY2goeCwgeSkKI2RlZmluZSBDdXJsX3NzaF9pbml0KCkgMAojZW5kaWYgLyogVVNFX1NTSCAqLwoKI2VuZGlmIC8qIEhFQURFUl9DVVJMX1NTSF9IICovCg==')
OLD = b'#include <libssh/libssh.h>\n'
NEW = b'#include <libssh/libssh.h>\n#include <libssh/scp.h>\n'
AFTER = BEFORE.replace(OLD, NEW)
BEFORE_SHA256 = 'b83762900d9930c20d80c2fc610a990304e805b9b151e5e28578eef8138626d9'
AFTER_SHA256 = '2ca85b8d650cbc7fcaf9ee759d27ff0e9a82707360827db0159b5f9bcc374007'
HEADERS = {'libssh.h': {'sourcePath': 'include/libssh/libssh.h', 'bytes': 39665, 'sha256': 'e62f0421e365e50062c95dd07282aab4ee7746433fe8b82927786b6b2d7c5728'}, 'scp.h': {'sourcePath': 'include/libssh/scp.h', 'bytes': 3388, 'sha256': '482ba2f1d6a3249f6a165ee0b4b720ce98815a7ce5111f29b8265ae4616b8083'}}
MAX_FILE = 65536
BASE_NAMES = {'libssh.h', 'scp.h', 'libssh-install-receipt.json'}
PATCH_NAMES = BASE_NAMES | {'curl-ssh.before.h', 'curl-ssh.after.h', 'curl-patch-receipt.json'}
FINAL_NAMES = PATCH_NAMES | {'curl-install-receipt.json'}


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def encode(value):
    return (json.dumps(value, sort_keys=True, indent=2) + '\n').encode()


def directory(value):
    p = Path(value)
    if not p.is_absolute() or any(x in ('.', '..') for x in p.parts):
        raise RuntimeError('Expected an absolute source/install/material directory')
    current = Path(p.anchor)
    for part in p.parts[1:]:
        current = current / part
        s = current.lstat()
        if not stat.S_ISDIR(s.st_mode) or stat.S_ISLNK(s.st_mode):
            raise RuntimeError('Source/install/material directory is not real')
    return p


def read_file(path):
    directory(path.parent)
    fd = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
    try:
        first = os.fstat(fd)
        if not stat.S_ISREG(first.st_mode) or not 0 < first.st_size <= MAX_FILE:
            raise RuntimeError('Source/material file type or extent changed')
        with os.fdopen(fd, 'rb', closefd=False) as stream:
            raw = stream.read(MAX_FILE + 1)
        last = os.fstat(fd)
        if (first.st_dev, first.st_ino, first.st_size, first.st_mtime_ns) != (last.st_dev, last.st_ino, last.st_size, last.st_mtime_ns) or len(raw) != first.st_size:
            raise RuntimeError('Source/material bytes changed during read')
        return raw
    finally:
        os.close(fd)


def write_new(path, raw):
    directory(path.parent)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    try:
        with os.fdopen(fd, 'wb', closefd=False) as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(fd)
    finally:
        os.close(fd)


def git(source, *args):
    env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
    env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_SYSTEM=os.devnull,
               GIT_CONFIG_GLOBAL=os.devnull, GIT_TERMINAL_PROMPT='0')
    result = subprocess.run(['git', '-c', 'core.hooksPath=' + os.devnull,
                             '-c', 'core.attributesFile=' + os.devnull,
                             '-c', 'core.autocrlf=false', '-C', str(source), *args],
                            env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, check=False)
    if result.returncode != 0 or len(result.stdout) > MAX_FILE:
        raise RuntimeError('Exact curl/libssh source identity check failed')
    return result.stdout


def check_identity(source, commit, tree, changed):
    if git(source, 'rev-parse', 'HEAD').strip().decode('ascii') != commit or git(source, 'rev-parse', 'HEAD^{tree}').strip().decode('ascii') != tree:
        raise RuntimeError('Dependency is not the selected immutable commit/tree')
    actual = [x for x in git(source, 'diff', '--no-ext-diff', '--no-textconv', '--name-only', '-z', 'HEAD', '--').split(b'\0') if x]
    if sorted(actual) != sorted(changed):
        raise RuntimeError('Unexpected tracked dependency source modifications')


def check_curl(source, patched):
    check_identity(source, CURL_COMMIT, CURL_TREE, [TARGET.encode()] if patched else [])
    if git(source, 'show', CURL_COMMIT + ':' + TARGET) != BEFORE or read_file(source / TARGET) != (AFTER if patched else BEFORE):
        raise RuntimeError('Complete canonical or actual curl public-header seam changed')


def check_headers(prefix, materials=None):
    result = {}
    for name, pin in HEADERS.items():
        raw = read_file(prefix / 'include/libssh' / name)
        if len(raw) != pin['bytes'] or sha(raw) != pin['sha256']:
            raise RuntimeError('Installed complete libssh public header changed')
        if materials is not None and read_file(materials / name) != raw:
            raise RuntimeError('Installed libssh header no longer matches retained source')
        result[name] = raw
    return result


def receipt(state):
    return {'schema': 1, 'kind': 'BILIPAI_CURL_LIBSSH_SCP_HEADER_SOURCE',
            'state': state, 'curlCommit': CURL_COMMIT, 'curlTree': CURL_TREE,
            'libsshCommit': LIBSSH_COMMIT, 'libsshTree': LIBSSH_TREE,
            'targetPath': TARGET, 'beforeSha256': BEFORE_SHA256,
            'afterSha256': AFTER_SHA256, 'libsshHeaders': HEADERS,
            'helperSha256': sha(read_file(Path(__file__))), 'reviewedPatchCount': 1,
            'protocolsDisabled': False, 'runtimeTested': False, 'gpuExecuted': False}


def check_materials(materials, names):
    if {p.name for p in materials.iterdir()} != names:
        raise RuntimeError('Retained compatibility material inventory changed')
    for name, pin in HEADERS.items():
        raw = read_file(materials / name)
        if len(raw) != pin['bytes'] or sha(raw) != pin['sha256']:
            raise RuntimeError('Retained complete libssh public header changed')
    if read_file(materials / 'libssh-install-receipt.json') != encode(receipt('AFTER_REAL_LIBSSH_INSTALL_BEFORE_RECIPE_CLEANUP')):
        raise RuntimeError('Exact libssh install/source witness changed')
    if names != BASE_NAMES:
        if read_file(materials / 'curl-ssh.before.h') != BEFORE or read_file(materials / 'curl-ssh.after.h') != AFTER:
            raise RuntimeError('Retained complete curl header relation changed')
        if read_file(materials / 'curl-patch-receipt.json') != encode(receipt('CURL_PATCH_APPLIED_BEFORE_CONFIGURE')):
            raise RuntimeError('Exact curl patch/source witness changed')


def main():
    p = argparse.ArgumentParser()
    p.add_argument('mode', choices=('libssh-after-install', 'patch-curl', 'curl-after-install'))
    p.add_argument('source')
    p.add_argument('prefix')
    p.add_argument('materials')
    args = p.parse_args()
    source, prefix = directory(args.source), directory(args.prefix)
    material_path = Path(args.materials)
    if not material_path.is_absolute() or material_path == source or source in material_path.parents or material_path == prefix or prefix in material_path.parents:
        raise RuntimeError('Retained materials must be outside dependency cleanup and install tree')
    directory(material_path.parent)
    if (sha(BEFORE) != BEFORE_SHA256 or sha(AFTER) != AFTER_SHA256 or BEFORE.count(OLD) != 1 or AFTER.count(NEW) != 1 or AFTER.replace(NEW, OLD) != BEFORE):
        raise RuntimeError('Reviewed exact public-header compatibility relation changed')
    if args.mode == 'libssh-after-install':
        if material_path.exists():
            raise RuntimeError('Use a fresh compatibility material directory')
        check_identity(source, LIBSSH_COMMIT, LIBSSH_TREE, [])
        installed = check_headers(prefix)
        for name, pin in HEADERS.items():
            canonical = git(source, 'show', LIBSSH_COMMIT + ':' + pin['sourcePath'])
            if canonical != installed[name] or read_file(source / pin['sourcePath']) != canonical:
                raise RuntimeError('Actual/install/canonical complete libssh header differs')
        material_path.mkdir(mode=0o700)
        materials = directory(material_path)
        for name, raw in installed.items():
            write_new(materials / name, raw)
        write_new(materials / 'libssh-install-receipt.json', encode(receipt('AFTER_REAL_LIBSSH_INSTALL_BEFORE_RECIPE_CLEANUP')))
    else:
        materials = directory(material_path)
        check_materials(materials, BASE_NAMES if args.mode == 'patch-curl' else PATCH_NAMES)
        check_headers(prefix, materials)
        check_curl(source, args.mode != 'patch-curl')
        if args.mode == 'patch-curl':
            write_new(materials / 'curl-ssh.before.h', BEFORE)
            write_new(materials / 'curl-ssh.after.h', AFTER)
            fd = os.open(source / TARGET, os.O_RDWR | getattr(os, 'O_NOFOLLOW', 0))
            try:
                first = os.fstat(fd)
                if not stat.S_ISREG(first.st_mode) or first.st_size != len(BEFORE) or os.read(fd, MAX_FILE + 1) != BEFORE:
                    raise RuntimeError('Actual curl target changed before patch')
                os.lseek(fd, 0, os.SEEK_SET)
                with os.fdopen(fd, 'wb', closefd=False) as stream:
                    stream.write(AFTER)
                    stream.truncate()
                    stream.flush()
                    os.fsync(fd)
            finally:
                os.close(fd)
            check_curl(source, True)
            write_new(materials / 'curl-patch-receipt.json', encode(receipt('CURL_PATCH_APPLIED_BEFORE_CONFIGURE')))
        else:
            write_new(materials / 'curl-install-receipt.json', encode(receipt('AFTER_REAL_CURL_INSTALL_BEFORE_RECIPE_CLEANUP')))


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Local SOURCE provider. No CLI/private-key export, generation or publication.

Explicit input contract: an existing RAW Windows DPAPI blob protected with
CurrentUser scope and NULL optional entropy. No wrapper/base64/plaintext/entropy
fallback. Historical provision metadata does not prove that its existing file
satisfies this new contract: only later actual DPAPI success and the pinned
JDK Ed25519 sign+public-key verification can accept that key. Never run merely
for inspection. Caller must finish all full-app acceptance before invoking us.
"""
from __future__ import annotations

import base64
from contextlib import contextmanager
import ctypes
from ctypes import wintypes
import hashlib
import json
import os
from pathlib import Path
import re
import stat

KEY_ID = "veyra-compatible-v1-102b3faa4e9e82a5"
REPOSITORY = "tonysuper666-creator/BiliPai-Windows"
PUBLIC_SPKI = "MCowBQYDK2VwAyEAxA5zYD641A+LJEOBdlc5Dg9I6szOsSArcBjLEapZ6Cc="
PUBLIC_SPKI_SHA256 = "102b3faa4e9e82a5071697361242065222e64c466796860cbca878453de2caa6"
STORAGE_CONTRACT = "RAW_DPAPI_CURRENT_USER_NULL_ENTROPY_V1"
MAX_PROTECTED = 64 * 1024
MAX_PKCS8 = 4096
CRYPTPROTECT_UI_FORBIDDEN = 1
LOAD_LIBRARY_SEARCH_SYSTEM32 = 0x800


class LocalKeyError(ValueError):
    """Fixed portable failure only; no path/blob/native error details."""


def require(condition, code):
    if not condition:
        raise LocalKeyError(code)


def digest(value):
    require(type(value) is str and re.fullmatch(r"[0-9a-f]{64}", value), "LOCAL_TRUST_DIGEST_REQUIRED")
    return value


def unique_object(pairs):
    value = {}
    for name, item in pairs:
        require(name not in value, "LOCAL_PUBLIC_RECORD_REJECTED")
        value[name] = item
    return value


def no_links(path):
    path = Path(path)
    require(path.is_absolute(), "LOCAL_ABSOLUTE_PATH_REQUIRED")
    for part in (path, *path.parents):
        info = part.lstat()
        require(not stat.S_ISLNK(info.st_mode) and
                not (getattr(info, "st_file_attributes", 0) & 0x400), "LOCAL_LINK_PATH_REJECTED")
    return path


def bounded_file(path, maximum):
    path = no_links(path)
    with path.open("rb") as stream:
        before = os.fstat(stream.fileno())
        require(stat.S_ISREG(before.st_mode) and before.st_nlink == 1 and
                0 < before.st_size <= maximum, "LOCAL_FILE_SHAPE_REJECTED")
        value = bytearray(stream.read(maximum + 1))
        after = os.fstat(stream.fileno())
        require((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) ==
                (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns) and
                len(value) == before.st_size, "LOCAL_FILE_CHANGED")
        return value


def pinned_jdk21(java_executable, trusted_java_sha256):
    # The hash is supplied independently by the operator, never taken from the
    # executable, release metadata, public key record or a neighboring sidecar.
    # This pins java.exe, not every JDK DLL/module: the operator must provide a
    # trusted, immutable complete JDK installation and protect it from replacement.
    executable = no_links(java_executable)
    require(executable.name.lower() == "java.exe", "LOCAL_JDK_EXECUTABLE_REQUIRED")
    with executable.open("rb") as stream:
        actual = hashlib.file_digest(stream, "sha256").hexdigest()
    require(actual == digest(trusted_java_sha256), "LOCAL_JDK_EXECUTABLE_MISMATCH")
    release = bounded_file(executable.parent.parent / "release", 64 * 1024)
    try:
        text = release.decode("utf-8")
        versions = [line for line in text.splitlines() if line.startswith("JAVA_VERSION=")]
        require(len(versions) == 1 and
                re.fullmatch(r'JAVA_VERSION="21(?:[.\+_-][^"\r\n]*)?"', versions[0]),
                "LOCAL_JDK21_REQUIRED")
    finally:
        release[:] = b"\0" * len(release)
    return executable


def protected_path(public_record, trusted_record_sha256, storage_contract):
    require(os.name == "nt" and storage_contract == STORAGE_CONTRACT, "LOCAL_STORAGE_CONTRACT_REJECTED")
    raw = bounded_file(public_record, 16 * 1024)
    try:
        require(hashlib.sha256(raw).hexdigest() == digest(trusted_record_sha256), "LOCAL_PUBLIC_RECORD_HASH_MISMATCH")
        record = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object,
                            parse_constant=lambda unused: (_ for _ in ()).throw(LocalKeyError("LOCAL_PUBLIC_RECORD_REJECTED")))
        allowed = {"schemaVersion", "state", "repository", "algorithm", "keyId", "publicSpkiBase64",
                   "publicSpkiSha256", "generatedAt", "privateKeyProtection", "protectedKeyPath",
                   "privateRawFileRemoved", "keyPossessionVerified", "productTestsExecuted",
                   "signatureOrCatalogPublished", "cloudSecretConfigured", "sdkOrGpuExecuted", "desktopReplaced"}
        require(type(record) is dict and set(record) in (allowed, allowed | {"storageContract"}) and
                type(record.get("schemaVersion")) is int and
                record["schemaVersion"] == 1 and record.get("state") ==
                "OWN_KEY_PROVISIONED_LOCALLY_NOT_PUBLISHED_OR_CLOUD_CONFIGURED" and
                record.get("repository") == REPOSITORY and record.get("algorithm") == "Ed25519" and
                record.get("keyId") == KEY_ID and record.get("publicSpkiBase64") == PUBLIC_SPKI and
                record.get("publicSpkiSha256") == PUBLIC_SPKI_SHA256 and
                record.get("privateKeyProtection") == "Windows DPAPI CurrentUser + new-directory current-user ACL" and
                record.get("privateRawFileRemoved") is True and record.get("keyPossessionVerified") is True,
                "LOCAL_PUBLIC_RECORD_REJECTED")
        require(hashlib.sha256(base64.b64decode(PUBLIC_SPKI, validate=True)).hexdigest() == PUBLIC_SPKI_SHA256,
                "LOCAL_PUBLIC_SOURCE_PIN_MISMATCH")
        # A future explicit record may name only this exact storage contract;
        # absence in the old record is NOT evidence of its existing blob format.
        require(record.get("storageContract", STORAGE_CONTRACT) == STORAGE_CONTRACT,
                "LOCAL_STORAGE_CONTRACT_REJECTED")
        path = record.get("protectedKeyPath")
        require(type(path) is str and "\0" not in path, "LOCAL_PROTECTED_PATH_REJECTED")
        path = no_links(Path(path))
        require(path.name == KEY_ID + ".dpapi" and path.parent.name == "private-signing",
                "LOCAL_PROTECTED_PATH_REJECTED")
        return path
    finally:
        raw[:] = b"\0" * len(raw)


class DataBlob(ctypes.Structure):
    _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_ubyte))]


@contextmanager
def private_pkcs8(public_record, trusted_record_sha256, storage_contract,
                  java_executable, trusted_java_sha256):
    """Yield only bounded PKCS8 DER in memory to the pinned producer leaf.

    No private bytes in stdout/log/disk/argv/environment. All exceptions are
    portable. The caller frames this bounded DER in its existing BVC1 pipe to
    the exact source-pinned JDK Ed25519 helper; that helper signs then verifies
    against the app's fixed SPKI before returning an envelope. DPAPI success
    alone never proves key identity or catalog acceptance. No full heap erasure
    claim: Python/JDK/key objects/subprocess may retain copies.
    """
    encrypted = None
    private_der = None
    output = DataBlob()
    kernel = None
    try:
        path = protected_path(public_record, trusted_record_sha256, storage_contract)
        pinned_jdk21(java_executable, trusted_java_sha256)
        encrypted = bounded_file(path, MAX_PROTECTED)
        buffer = (ctypes.c_ubyte * len(encrypted)).from_buffer(encrypted)
        source = DataBlob(len(encrypted), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_ubyte)))
        # System32 only, stdcall, lazy load strictly after full app acceptance.
        crypt = ctypes.WinDLL("crypt32.dll", use_last_error=True, winmode=LOAD_LIBRARY_SEARCH_SYSTEM32)
        kernel = ctypes.WinDLL("kernel32.dll", use_last_error=True, winmode=LOAD_LIBRARY_SEARCH_SYSTEM32)
        decrypt = crypt.CryptUnprotectData
        decrypt.argtypes = [ctypes.POINTER(DataBlob), ctypes.c_void_p, ctypes.POINTER(DataBlob),
                            ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(DataBlob)]
        decrypt.restype = wintypes.BOOL
        kernel.LocalFree.argtypes = [ctypes.c_void_p]
        kernel.LocalFree.restype = ctypes.c_void_p
        # CurrentUser/NULL entropy is the explicit *input protection* contract;
        # there is no machine-scope retry, alternate entropy or plaintext path.
        require(decrypt(ctypes.byref(source), None, None, None, None,
                        CRYPTPROTECT_UI_FORBIDDEN, ctypes.byref(output)), "LOCAL_DPAPI_REJECTED")
        require(bool(output.pbData) and 1 <= output.cbData <= MAX_PKCS8, "LOCAL_PKCS8_SIZE_REJECTED")
        private_der = bytearray(output.cbData)
        target = (ctypes.c_ubyte * len(private_der)).from_buffer(private_der)
        ctypes.memmove(target, output.pbData, output.cbData)
        yield private_der
    except LocalKeyError:
        raise
    except (OSError, ValueError, TypeError, AttributeError, OverflowError):
        raise LocalKeyError("LOCAL_PROVIDER_REJECTED") from None
    finally:
        if private_der is not None:
            private_der[:] = b"\0" * len(private_der)
        if output.pbData and kernel is not None:
            # DPAPI owns this actual native allocation; zero then LocalFree.
            ctypes.memset(output.pbData, 0, output.cbData)
            kernel.LocalFree(ctypes.cast(output.pbData, ctypes.c_void_p))
        if encrypted is not None:
            encrypted[:] = b"\0" * len(encrypted)

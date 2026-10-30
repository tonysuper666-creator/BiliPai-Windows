"""POSIX adapter for generators that unconditionally add Windows long path prefixes.

Same transformation as grokbot/linux-bootstrap; handles Python 3.12 as well.
No source text, hash checks, test discovery or assertions are changed.
"""
import os
import pathlib

if os.name != "nt":
    def strip(value):
        if isinstance(value, str) and value.startswith("\\\\?\\"):
            value = value[4:]
            if value.startswith("UNC\\"):
                value = "//" + value[4:]
        return value

    for cls in (pathlib.PurePath, pathlib.Path):
        if "__init__" in cls.__dict__:
            original = cls.__dict__["__init__"]
            def adapted(self, *args, _original=original, **kwargs):
                _original(self, *(strip(arg) for arg in args), **kwargs)
            cls.__init__ = adapted

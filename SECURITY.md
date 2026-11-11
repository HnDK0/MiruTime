# Security policy

## Supported versions

Only the latest published release receives security fixes.

## Reporting a vulnerability

Report privately through GitHub Security Advisories:
https://github.com/HnDK0/MiruTime/security/advisories/new

Do not open a public issue for a vulnerability.

Please include:

- affected version and Android version;
- a description of the impact and the attack path;
- minimal reproduction steps, PoC or affected code path;
- any suggested fix.

Never include API keys, cookies, tokens or personal data in the report. Plugin-generated error reports are redacted by the app; verify the content before attaching anything.

## Scope

In scope: the app and its modules, the plugin runtime sandbox, backup/restore, network handling, and handling of stored credentials.

Out of scope: third-party Lua plugins themselves (report those to their repository) and vulnerabilities in upstream libraries without a demonstrated impact on MiruTime.

# Security Policy

## Scope

Ayu Islands is a JetBrains IDE **UI theme plugin**. It contains no networking code, no data storage, and no authentication logic. The relevant security surface is limited to:

- Malicious code injection via theme JSON/XML definition files
- Supply chain attacks through Gradle dependencies
- Plugin distribution integrity (JetBrains Marketplace signing and verification)

## Supported Versions

| Version | Supported |
|---------|-----------|
| 2.x.x   | Yes      |
| < 2.0   | No       |

Only the latest major release line receives security fixes.

## Reporting a Vulnerability

**Preferred: GitHub Private Vulnerability Reporting**

Use the ["Report a vulnerability"](https://github.com/barad1tos/ayu-jetbrains/security/advisories/new) button in this repository's Security tab. This keeps your report confidential until a fix is available.

Please include:
- Description of the vulnerability
- Steps to reproduce
- Affected versions
- Potential impact

## What to Expect

- Acknowledgment within **7 days**
- Assessment and response within **30 days**
- For confirmed issues: a fix in the next release
- Credit in the changelog (unless you prefer anonymity)

## What Qualifies

- Code injection vectors in theme definition files
- Dependency vulnerabilities in the Gradle build chain
- Issues with plugin signing or distribution integrity

## What Does NOT Qualify

- Cosmetic bugs (wrong colors, UI glitches) — use [regular issue templates](https://github.com/barad1tos/ayu-jetbrains/issues/new/choose)
- JetBrains IDE vulnerabilities — report those to [JetBrains](https://www.jetbrains.com/privacy-security/)
- Theoretical attacks requiring physical access to the developer's machine

## Bug Bounty

This is a solo-developer open source project. There is no monetary bug bounty, but confirmed reporters will be credited in the release notes.

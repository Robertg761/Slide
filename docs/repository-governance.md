# Repository and release governance

The repository cannot safely configure its own GitHub trust boundary. A maintainer with repository
administration authority must apply the settings below in GitHub. They are deliberately documented
instead of being changed by a build or release workflow.

## Required GitHub settings

Create a ruleset for the default branch, `main`, with these controls:

- require pull requests and at least one approval;
- dismiss stale approvals when new commits are pushed;
- require the `CI / verify` status check and require the branch to be current before merging;
- block force pushes and deletion;
- do not allow bypass except a separately controlled emergency administrator role;
- require conversation resolution and prevent merge commits if linear history is the chosen policy.

Create a tag ruleset for `v*` that blocks update and deletion and limits tag creation to release
maintainers. Enable immutable releases in the repository release settings. The release workflow also
refuses to update an existing release, but server-side enforcement is the authoritative control.

Under Actions settings:

- keep the default workflow token read-only and grant write access only in the `publish` job;
- allow only GitHub-owned and explicitly approved actions, and require actions to be pinned to a
  full commit SHA;
- do not send repository secrets to workflows triggered from forks;
- enable Dependabot alerts and Dependabot security updates;
- keep secret scanning and push protection enabled.

## Release environment

Create an Actions environment named `release` and put all four signing values there:

- `SLIDE_KEYSTORE_B64`
- `SLIDE_SIGNING_STORE_PASSWORD`
- `SLIDE_SIGNING_KEY_ALIAS`
- `SLIDE_SIGNING_KEY_PASSWORD`

Require at least one release maintainer to approve deployments to this environment and prevent
self-review where the GitHub plan supports it. Limit deployment branches/tags to protected `v*`
tags. Do not duplicate signing secrets at repository or organization scope.

The release workflow intentionally builds and tests in a job that cannot access this environment.
Only the resulting unsigned artifact crosses into the signing job. That job does not check out the
repository or run Gradle while the key exists, deletes the temporary key before it runs the
repository-provided verifier, and publishes from a third job that never receives the key.

## Release procedure

1. Add the new version and a strictly greater Android `versionCode` to `release/versions.tsv`.
2. Set the same version and code in `app/build.gradle.kts`, and add a dated changelog section.
3. Merge through a pull request after `CI / verify` succeeds.
4. Create a protected `v<version>` tag at the current `main` commit.
5. Review the unprivileged build job, then approve the `release` environment deployment.
6. Confirm the final job reports the expected package, version, sole pinned model and checksum,
   STORED model compression, complete ABI set, release certificate, and APK SHA-256.
7. Download the live release asset and independently run `tools/verify_release_apk.sh` against it.

Never replace an asset or move a published tag. A correction gets a new SemVer and versionCode.

## Dependency changes

Dependabot opens weekly Gradle and Actions updates. Every dependency change must update both the
relevant Gradle lockfiles and `gradle/verification-metadata.xml` in the same reviewed pull request.
The reviewer must confirm unexpected new repositories, coordinates, checksums, native binaries,
and licence obligations before accepting generated metadata. Checksums establish byte identity;
they are not a vulnerability or maintainer-trust assessment.

## First-party licence

Slide's own source code and documentation are licensed under the Apache License, Version 2.0; the
complete terms are in the repository's `LICENSE` file. Packaged third-party notices remain separate
because dependencies, data, model weights, and corpora retain their own terms and attribution.
Changing the first-party licence requires explicit authorization from the copyright holder and must
never be inferred from a dependency licence or automated release task.

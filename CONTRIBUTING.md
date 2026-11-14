# Contributing to the Askurious diary app

Thank you for your interest. The app is developed by Philipp Brauner and Ralf Philipsen at the Chair of
Communication Science, RWTH Aachen University. RWTH Aachen University holds the rights in the code and licenses it
under the European Union Public Licence 1.2 ([LICENSE](LICENSE); the parts under other licences are listed at its
top and in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)).

## Before you open a pull request

The same rules apply as in the main repository
([CONTRIBUTING.md of askurious/askurious](https://github.com/askurious/askurious/blob/main/CONTRIBUTING.md)):

- Open an issue first and describe what you would like to change. We answer there whether we can take it.
- With a pull request you confirm that you wrote the contribution yourself (or may contribute it) and license it to
  RWTH Aachen University under the terms named there.
- Sign off every commit (`git commit -s`, [Developer Certificate of Origin 1.1](https://developercertificate.org/)).
- Do not include code, texts, images or data of others unless their licence allows it; name them and their licence
  in the pull request. A new or changed library also goes into [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md),
  `app/src/main/res/raw/notices.txt` and `gradle/verification-metadata.xml` (README, "Build and run").

## How we work

- Every text of the app exists in all of its languages (`app/src/main/res/values*/strings.xml`).
- The app and the server share one contract (`app/diary_api.py` in the main repository): a change that needs both
  sides says so in its pull request.
- Run the checks before a pull request: `./gradlew testDebugUnitTest lintDebug`.
- No concepts, audit reports or other planning documents in the repository.

## Conduct and security

Everyone who writes in issues, pull requests and reviews follows the [code of conduct](CODE_OF_CONDUCT.md).
Please do not report security issues in public issues; see [SECURITY.md](SECURITY.md).

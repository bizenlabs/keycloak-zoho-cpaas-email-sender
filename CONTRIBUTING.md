# Contributing

Issues and pull requests are welcome.

- `mvn verify` and `scripts/integration-test.sh` pass before a pull request is merged;
  CI runs both.
- Commit messages and pull request titles follow
  [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/): `type(scope): summary`,
  with types feat, fix, docs, style, refactor, perf, test, build, ci, chore, revert.
  Pull requests are squash-merged, so the title is the commit.
- Supporting a new Keycloak release: build with `-Dkeycloak.version=<version>`, run the
  integration test with `KEYCLOAK_VERSION=<version>`, and add a row to the README's
  compatibility table.

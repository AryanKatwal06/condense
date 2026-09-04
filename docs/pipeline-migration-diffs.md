# Pipeline migration diffs

Phase 4 locks filtered corpus output in `condense/src/test/resources/corpus/golden/`.
`GoldenLockTest` fails `mvn test` on any byte difference.

If a migration must change output, update that one golden file and add a one-line
reason here. Unexplained golden edits fail review.

| Catalog id | Why the locked bytes changed |
|---|---|
| — | No reviewed diffs. Goldens are the slice-0 pre-migration output. |

# Use PostgreSQL as the only database engine

Development, CI, and documented deployment will all use PostgreSQL 17. We deliberately reject an H2 development profile because the project's important behavior includes row locking, transactions, constraints, and Flyway migrations whose semantics must not vary between local tests and the documented runtime.


# Data availability

The repository includes the small simulation summaries, synthetic replay
inputs, and reviewed aggregate data used directly by the paper.

Raw production reconciliation logs, database identifiers, live Redis/Pika
snapshots, and operational records are not distributed. They contain customer
or operational metadata and are subject to access restrictions. The repository
provides reviewed aggregate CSV files, field descriptions, analysis scripts,
and synthetic replay materials as the public substitutes.

The rank-bucket data retains only paper profile labels, block counts, dirty
block counts, and normalized key-rank buckets. It does not contain task IDs,
schema names, table names, accounts, hosts, or source-to-target mappings.

Large simulation inputs are listed in `data/manifest.tsv`. They can be used
when a permanent public URL is available; the recorded byte count and SHA-256
must match before use.

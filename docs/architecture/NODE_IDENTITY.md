# Node Identity (implemented in T2)

- Key type: Ed25519 (OpenSSL 3 EVP). No custom crypto.
- `NodeID = SHA-256(raw 32-byte Ed25519 public key)`, shown as 64 hex chars.
- Storage: `<data_dir>/node.key`, PEM PKCS#8, created with `O_NOFOLLOW` and mode 0600;
  data dir is chmod 0700. Loading refuses keys readable by group/others, symlinks, non-Ed25519 keys.
- Key is **not encrypted at rest** yet (planned: Android Keystore / OS keyring).
- Tests: sign/verify, tamper detection, persistence, permission enforcement, RFC 8032 TEST 1 vector.

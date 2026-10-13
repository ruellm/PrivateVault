# Container feasibility note: TrueCrypt and VeraCrypt volumes on Android

Desk research only, written 2026-10-12. No code. This note answers whether a TrueCrypt or
VeraCrypt style container could be the storage foundation for PrivateVault on an unrooted
Android device, and records why Phase 1 uses application-managed encrypted objects instead.

## 1. Mounting a volume is not available to ordinary Android apps

TrueCrypt and VeraCrypt work by presenting a decrypted block device to the operating system
and letting the OS mount a filesystem on it. On desktop systems this needs a kernel driver
(Windows) or device-mapper access (Linux). On Android:

- Mounting a filesystem requires kernel privileges (`CAP_SYS_ADMIN`). App processes run in a
  restricted SELinux domain and do not have them. No API in the Android SDK lets an app mount
  anything. The system's own FUSE based storage layer is a system service, not an app facility.
- Root, a custom kernel, or a custom ROM would be needed for a real mount. Section 2.2 of the
  handoff rules these out: the product must run on a normal unrooted phone.
- The platform alternative is the Storage Access Framework. An app can implement a
  `DocumentsProvider` that exposes a virtual folder tree to other apps. This is a userspace
  file API, not a mount, and the app itself must do all decryption and filesystem parsing.
  Third party apps that "mount" VeraCrypt USB drives on Android (for example OTG Master on
  F-Droid, checked 2026-10-12) do exactly this: they read raw sectors through the USB host API
  and parse FAT32 or exFAT in their own code.

Conclusion: there is no mount path for an ordinary app. "Container support" on Android can only
mean reading the container format in userspace.

## 2. What reading a container in userspace would require

A userspace reader would have to reimplement three layers:

1. **Volume header parsing.** The VeraCrypt header is 512 bytes of salt plus an encrypted block
   holding the master keys, the volume size, and the data offset. Opening it means trying every
   supported PBKDF2 or Argon2 variant and cipher cascade until the header CRC checks out.
   That is a loop over header formats with security relevant details (hidden volumes, legacy
   modes, key derivation iteration counts).
2. **The XTS cipher layer.** Sector by sector AES (or Serpent, Twofish, Camellia, Kuznyechik,
   and their cascades) in XTS mode, with the sector number as tweak. libsodium does not provide
   AES-XTS or any of the other ciphers. The project would need a second cryptographic library or
   its own implementation, which Section 2.5 of the handoff forbids.
3. **A FAT or exFAT filesystem layer.** Directory tables, allocation chains, long file names,
   timestamps, and write support with consistency guarantees on a format that has no journal.
   Filesystem code is large and a classic source of memory safety bugs when fed untrusted
   images.

Each layer is a security relevant parser of attacker controlled bytes, and none of it is
"use an established library as documented". It is a port of a large discontinued code base.

## 3. XTS gives confidentiality without integrity

XTS mode is a length preserving tweakable block cipher mode designed for disk encryption, where
the ciphertext must be the same size as the plaintext. It has no authentication tag. A modified
sector decrypts to garbage without any error. Bit flips, partial overwrites, and rollback of
individual sectors are not detected by the cipher layer; only higher level checksums (if any)
would notice.

Phase 1 requires that tampering, truncation, and trailing data fail closed. That is exactly what
authenticated encryption gives and what XTS does not. Adding integrity on top of a container
(a separate hash tree, for example) would be a new construction the project would have to
design and defend.

## 4. One large container conflicts with per-item features

Later phases need per-item permission, per-item sharing, per-item revocation, and per-item
cryptographic erasure. In a single container every file is encrypted under the same volume
master key:

- Sharing one photo means either sharing the whole volume key or re-encrypting the item anyway.
- Erasing one item means overwriting its sectors and hoping the flash controller actually
  discards them, which cannot be promised. With per-object keys, deleting the wrapped key is
  enough to make the ciphertext unreadable.
- Streaming one item to another device requires reading through the filesystem layer rather
  than reading one self contained object.
- A fixed size container wastes space or needs resizing logic; a growing set of objects does not.

Individually encrypted objects, each with its own key wrapped by the vault key, map directly to
these needs. This is the design in `docs/PHASE1_DESIGN.md`.

## 5. Licensing

Checked on 2026-10-12 at https://veracrypt.io/en/VeraCrypt%20License.html:

- VeraCrypt as a whole is governed by the TrueCrypt License 3.0, with Apache License 2.0 also
  offered for parts written by the VeraCrypt authors. Third party pieces (LZMA SDK, zlib, an
  LGPL component) keep their own licenses.
- The TrueCrypt License 3.0 is not an OSI approved license. It requires "Based on TrueCrypt"
  attribution with a link to truecrypt.org, bans use of the TrueCrypt name and logos, requires
  full public source for derivatives, and forbids combining with licenses that would weaken its
  terms. VeraCrypt adds its own name restrictions.
- These terms are workable for an open source fork but awkward for a product that is not itself
  a TrueCrypt derivative, and they would attach to any ported header, XTS, or filesystem code.
- libsodium is ISC licensed, which has no such obligations.

## 6. Conclusion

A TrueCrypt or VeraCrypt container is not a viable foundation for PrivateVault on Android:

- no mount for unrooted apps;
- a userspace reader is a large port of security sensitive code and needs ciphers libsodium
  does not provide;
- XTS has no integrity, which the tamper detection requirement needs;
- a single volume works against per-item sharing, revocation, and erasure;
- the inherited license carries obligations the project does not want.

TrueCrypt remains a reference for ideas (plausible deniability, header layout discipline,
key derivation hardening) but not for code.

### Worth revisiting later?

Only as an optional export or backup format, and only if users ask for it. A possible Phase 6
feature would be "export selected items into a VeraCrypt container for offline archival",
implemented by writing a container, which still needs an XTS implementation and a FAT writer.
The cost is the same as above and the benefit is interoperability with desktop tools, not
security. Import of existing containers is not planned. Neither is a Phase 1 or Phase 2
concern, and nothing in the Phase 1 object format depends on this decision.

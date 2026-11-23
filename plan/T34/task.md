# T34. Decode PNG through a shared, allocation-capped decoder

Status: not started

PNG decode for iTerm2 images and kitty `f=100` goes through one shared decoder: read the header first, compute the RGBA length from the size before allocating a pixel buffer, then decode with a hard allocation cap. Errors map to `ImageError.Decode`. Test fixtures can encode through the same helper; grey and palette PNGs stay covered.

Acceptance:
- [ ] Header-first, size-capped PNG decode lives in a shared module used by `scull-image`.
- [ ] Tests cover cap failures, grey and palette fixtures, and sRGB output.
- [ ] `./gradlew check` passes.

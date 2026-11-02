# Product photos for the demo catalog

The 24 seeded products ship with real photos in `services/catalog-service/app/photos/<id>.jpg`.
catalog-service loads them into MongoDB (GridFS) once, at startup. It skips any product the admin
has renamed or given its own photo, and a photo the admin deletes stays deleted.

All photos are free Unsplash photos (Unsplash License, no Unsplash+), cropped to 900×900, with any
visible third-party logo painted out. Sources are listed in `photos/CREDITS.md`.

## Changing a photo

The easy way is the admin panel: upload a new photo for the product. That always wins.

To change a bundled photo instead:

1. Push to the `product-photos` branch. The `product-photos` workflow does the downloading, because
   the cluster and most sandboxes can't reach Unsplash.
2. Add the product's candidates to `seeds.json` (Unsplash `photo-...` IDs from the photo's
   `images.unsplash.com` URL; never `plus.unsplash.com`, which is paid), and remove its entry from
   `picks.json`. The workflow then writes numbered contact sheets to `candidates/`.
3. Set the product's entry in `picks.json`:
   `{"pick": <index on the sheet>, "x": 0.5, "y": 0.5, "zoom": 1.0, "blur": [[x0, y0, x1, y1]]}`.
   `x`/`y`/`zoom` move the square crop. Each `blur` box (on the 900px result) paints out a logo; add
   `"v"` as a 5th item for curved or shaded surfaces, or `"b"` for a soft blur on something in motion.
4. The workflow commits the new `photos/<id>.jpg`. Then bump `PHOTO_SET` in
   `services/catalog-service/app/main.py` and merge. On rollout, products still showing the old
   bundled photo get the new one.

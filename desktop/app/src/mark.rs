// SPDX-License-Identifier: GPL-3.0-or-later
//! The NJLINK mark, rendered at any pixel size from the master icon.

const MASTER_SIZE: usize = 256;
const MASTER_RGBA: &[u8; MASTER_SIZE * MASTER_SIZE * 4] = include_bytes!("icon_256.raw");

/// Renders the mark as straight-alpha RGBA, row by row, `size`×`size`.
pub fn render(size: usize) -> Vec<u8> {
    if size == MASTER_SIZE {
        return MASTER_RGBA.to_vec();
    }
    let mut out = vec![0u8; size * size * 4];
    for py in 0..size {
        let sy = ((py as f32 + 0.5) * (MASTER_SIZE as f32 / size as f32) - 0.5).clamp(0.0, (MASTER_SIZE - 1) as f32);
        let y0 = sy.floor() as usize;
        let y1 = (y0 + 1).min(MASTER_SIZE - 1);
        let fy = sy - y0 as f32;

        for px in 0..size {
            let sx = ((px as f32 + 0.5) * (MASTER_SIZE as f32 / size as f32) - 0.5).clamp(0.0, (MASTER_SIZE - 1) as f32);
            let x0 = sx.floor() as usize;
            let x1 = (x0 + 1).min(MASTER_SIZE - 1);
            let fx = sx - x0 as f32;

            let w00 = (1.0 - fx) * (1.0 - fy);
            let w10 = fx * (1.0 - fy);
            let w01 = (1.0 - fx) * fy;
            let w11 = fx * fy;

            let i00 = (y0 * MASTER_SIZE + x0) * 4;
            let i10 = (y0 * MASTER_SIZE + x1) * 4;
            let i01 = (y1 * MASTER_SIZE + x0) * 4;
            let i11 = (y1 * MASTER_SIZE + x1) * 4;

            let out_i = (py * size + px) * 4;
            for c in 0..4 {
                let v = w00 * MASTER_RGBA[i00 + c] as f32
                    + w10 * MASTER_RGBA[i10 + c] as f32
                    + w01 * MASTER_RGBA[i01 + c] as f32
                    + w11 * MASTER_RGBA[i11 + c] as f32;
                out[out_i + c] = v.round().clamp(0.0, 255.0) as u8;
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn renders_expected_lengths() {
        for size in [16, 20, 24, 32, 40, 48, 64, 128, 256] {
            let img = render(size);
            assert_eq!(img.len(), size * size * 4);
            assert!(img.iter().any(|&b| b > 0));
        }
    }
}

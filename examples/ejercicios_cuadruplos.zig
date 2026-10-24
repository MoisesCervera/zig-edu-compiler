pub fn main() void {
    const A: i32 = 1;
    const B: i32 = 2;
    const C: i32 = 3;
    const D: i32 = 4;
    const E: i32 = 5;
    const F: i32 = 6;
    const G: i32 = 7;
    const H: i32 = 8;

    var resultado: i32 = A + B;
    resultado = A + B * C;
    resultado = (A + B) * C;
    resultado = A * B + C / D;
    resultado = (A + B) * (C - D);
    resultado = A + (B - C) * D;
    resultado = (A - B) / C + D;
    resultado = A * (B + C) - D / E;
    resultado = (A + B) / (C - D) + E;
    resultado = A + B * (C - D) / E;
    resultado = ((A + B) * C) - D;
    resultado = (A - B) * (C + D) / (E - F);
    resultado = A + B * (C * D - E) / (F + G) - H;
    resultado = ((A + B) / C) * (D - E);
    resultado = ((A - B) * (C + D)) * (E / F);
}
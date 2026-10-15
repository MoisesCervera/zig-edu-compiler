pub fn main() void {
    var x: i32 = 5 + 4 * 8 / 4 - 16;
    x += (2 + 3) * 4;
    const negado: i32 = -x;
    const condicion: bool = x > 0 and negado < 0;
    _ = condicion;
}

fn sumar(a: i32, b: i32) i32 {
    return a + b;
}
fn elegir(n: i32) i32 {
    switch (n) {
        0 => { return 10; },
        1, 2 => { return 20; },
        else => { return -1; },
    }
}
pub fn main() void {
    var datos = [_]i32{1, 2, 3, 4};
    datos[1] += 5;
    var total: i32 = 0;
    for (datos) |dato| {
        total += dato;
    }
    for (0..datos.len) |i| {
        if (i == 1) { continue; }
        total += datos[i];
    }
    var j: i32 = 0;
    while (j < 5) {
        j += 1;
        if (j == 4) { break; }
        total += j;
    }
    if (total > 10 and j > 0) {
        total = sumar(sumar(total, 1), elegir(2));
    } else {
        total = -total;
    }
    _ = total;
}

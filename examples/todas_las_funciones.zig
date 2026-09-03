fn add(left: i32, right: i32) i32 {
    return left + right;
}

fn subtract(left: i32, right: i32) i32 {
    return left - right;
}

fn multiply(left: i32, right: i32) i32 {
    return left * right;
}

fn divide(left: u8, right: u8) u8 {
    return left / right;
}

fn remainder(left: u8, right: u8) u8 {
    return left % right;
}

fn weightedOperation(first: f64, second: f64, third: f64) f64 {
    const product = second * third;
    const quotient = product / 2.0;
    return first + quotient - 1.0;
}

fn absolute(value: i32) i32 {
    if (value < 0) {
        return -value;
    }
    return value;
}

fn clamp(value: i32, minimum: i32, maximum: i32) i32 {
    if (value < minimum) {
        return minimum;
    } else if (value > maximum) {
        return maximum;
    } else {
        return value;
    }
}

fn sign(value: i32) i32 {
    switch (value) {
        -1 => {
            return -1;
        },
        0 => {
            return 0;
        },
        1 => {
            return 1;
        },
        else => {
            if (value < 0) {
                return -1;
            }
            return 1;
        },
    }
}

fn factorial(value: i32) i32 {
    var cursor: i32 = value;
    var result: i32 = 1;
    while (cursor > 1) {
        result *= cursor;
        cursor -= 1;
    }
    return result;
}

fn fibonacci(position: i32) i32 {
    if (position <= 1) {
        return position;
    }
    var previous: i32 = 0;
    var current: i32 = 1;
    var index: i32 = 2;
    while (index <= position) {
        const next = previous + current;
        previous = current;
        current = next;
        index += 1;
    }
    return current;
}

fn sumEight(values: [8]i32) i32 {
    var total: i32 = 0;
    for (values) |value| {
        total += value;
    }
    return total;
}

fn maximumEight(values: [8]i32) i32 {
    var maximum: i32 = values[0];
    for (values) |value| {
        if (value > maximum) {
            maximum = value;
        }
    }
    return maximum;
}

fn minimumEight(values: [8]i32) i32 {
    var minimum: i32 = values[0];
    for (values) |value| {
        if (value < minimum) {
            minimum = value;
        }
    }
    return minimum;
}

fn countPositive(values: [8]i32) i32 {
    var count: i32 = 0;
    for (values) |value| {
        if (value > 0) {
            count += 1;
        }
    }
    return count;
}

fn contains(values: [8]i32, target: i32) bool {
    for (values) |value| {
        if (value == target) {
            return true;
        }
    }
    return false;
}

fn firstEven(values: [8]u8) u8 {
    for (values) |value| {
        if (value % 2 != 0) {
            continue;
        }
        return value;
    }
    return 0;
}

fn sumUntilNegative(values: [8]i32) i32 {
    var total: i32 = 0;
    for (values) |value| {
        if (value < 0) {
            break;
        }
        total += value;
    }
    return total;
}

fn adjustCopy(values: [5]u8) u8 {
    var copy = values;
    copy[0] += 2;
    copy[1] -= 1;
    copy[2] *= 3;
    copy[3] /= 2;
    copy[4] %= 5;
    return copy[0] + copy[1] + copy[2] + copy[3] + copy[4];
}

fn averageFive(values: [5]f64) f64 {
    var total: f64 = 0.0;
    for (values) |value| {
        total = total + value;
    }
    const divisor: f64 = 5.0;
    return total / divisor;
}

fn isInside(value: i32, minimum: i32, maximum: i32) bool {
    return value >= minimum and value <= maximum;
}

fn shouldAccept(enabled: bool, blocked: bool, score: i32) bool {
    const hasScore = score >= 60;
    return enabled and !blocked and hasScore;
}

fn booleanCode(first: bool, second: bool) i32 {
    if (first and second) {
        return 3;
    } else if (first or second) {
        return 2;
    } else {
        return 1;
    }
}

fn gradeCode(score: i32) i32 {
    switch (score) {
        100 => {
            return 5;
        },
        90, 91, 92 => {
            return 4;
        },
        70, 71, 72 => {
            return 3;
        },
        60 => {
            return 2;
        },
        else => {
            return 1;
        },
    }
}

fn letterCode(letter: u8) i32 {
    switch (letter) {
        'A', 'a' => {
            return 1;
        },
        'B', 'b' => {
            return 2;
        },
        'C', 'c' => {
            return 3;
        },
        else => {
            return 0;
        },
    }
}

fn countLetter(word: [12]u8, searched: u8) i32 {
    var count: i32 = 0;
    for (word) |letter| {
        if (letter == searched) {
            count += 1;
        }
    }
    return count;
}

fn rangeExercise(limit: i32) i32 {
    var result: i32 = limit;
    for (0..10) |index| {
        _ = index;
        result += 1;
    }
    return result;
}

fn nestedConditions(value: i32) i32 {
    var result: i32 = value;
    if (value >= 0) {
        if (value == 0) {
            result = 100;
        } else {
            result += 10;
        }
    } else {
        result = absolute(value);
    }
    return result;
}

fn emptyAction(value: i32) void {
    const local = value + 1;
    _ = local;
    return;
}

pub fn main() void {
    const integers = [_]i32{ 12, -3, 7, 22, 5, 0, 9, 14 };
    const natural = [_]u8{ 12, 3, 7, 22, 5, 4, 9, 14 };
    const small = [5]u8{ 10, 20, 3, 8, 17 };
    const decimals = [_]f64{ 1.5, 2.5, 3.5, 4.5, 5.0 };
    const greeting = [_]u8{ 'A', 'u', 't', 'o', 'm', 'a', 't', 'a', 's', 'A', 'I', 'I' };
    const flags = [_]bool{ true, false, true, true };

    const arithmeticOne = add(8, 4);
    const arithmeticTwo = subtract(arithmeticOne, 2);
    const arithmeticThree = multiply(arithmeticTwo, 3);
    const unsignedDivision = divide(30, 5);
    const unsignedRemainder = remainder(31, 7);
    const weighted = weightedOperation(3.0, 8.0, 5.0);

    const absoluteValue = absolute(-25);
    const limited = clamp(120, 0, 100);
    const signed = sign(-8);
    const factorialValue = factorial(5);
    const fibonacciValue = fibonacci(10);

    const total = sumEight(integers);
    const maximum = maximumEight(integers);
    const minimum = minimumEight(integers);
    const positives = countPositive(integers);
    const hasSeven = contains(integers, 7);
    const even = firstEven(natural);
    const partial = sumUntilNegative(integers);
    const adjusted = adjustCopy(small);

    const average = averageFive(decimals);
    const inside = isInside(total, 0, 100);
    const accepted = shouldAccept(true, false, 75);
    const logic = booleanCode(flags[0], flags[1]);
    const grade = gradeCode(90);
    const letter = letterCode(greeting[0]);
    const repeated = countLetter(greeting, 'A');
    const ranged = rangeExercise(5);
    const nested = nestedConditions(-12);

    var accumulator: i32 = 0;
    accumulator += arithmeticOne;
    accumulator += arithmeticTwo;
    accumulator += arithmeticThree;
    accumulator += absoluteValue;
    accumulator += limited;
    accumulator += signed;
    accumulator += factorialValue;
    accumulator += fibonacciValue;
    accumulator += total;
    accumulator += maximum;
    accumulator += minimum;
    accumulator += positives;
    accumulator += partial;
    accumulator += adjusted;
    accumulator += logic;
    accumulator += grade;
    accumulator += letter;
    accumulator += repeated;
    accumulator += ranged;
    accumulator += nested;

    const unsignedResultsAreValid = unsignedDivision > 0 and unsignedRemainder < 7 and even > 0 and adjusted > 0;
    if (hasSeven and inside and accepted and unsignedResultsAreValid) {
        accumulator += 1;
    } else {
        accumulator -= 1;
    }

    emptyAction(accumulator);
    const decimalResult = average + weighted;
    _ = decimalResult;
    _ = greeting.len;
    _ = flags.len;
}

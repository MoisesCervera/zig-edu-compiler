package mx.edu.automatas.zigedu.analysis;

import mx.edu.automatas.zigedu.ast.Ast;
import mx.edu.automatas.zigedu.parser.ParseException;
import mx.edu.automatas.zigedu.parser.Token;
import mx.edu.automatas.zigedu.parser.TokenMgrError;
import mx.edu.automatas.zigedu.parser.ZigEduParser;
import mx.edu.automatas.zigedu.parser.ZigEduParserConstants;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SourceAnalyzer {
    private static final String[] TOKEN_NAMES = buildTokenNames();
    private static final Pattern LEXICAL_POSITION = Pattern.compile(
            "line\\s+(\\d+),\\s+column\\s+(\\d+).*?Encountered:\\s*(.*)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    public AnalysisResult analyze(String source) {
        String safeSource = source == null ? "" : source;
        List<TokenInfo> tokens = new ArrayList<>();
        List<Diagnostic> lexicalErrors = new ArrayList<>();
        List<Diagnostic> syntacticErrors = new ArrayList<>();

        collectTokens(safeSource, tokens, lexicalErrors);
        if (!lexicalErrors.isEmpty()) {
            return new AnalysisResult(tokens, Optional.empty(), lexicalErrors, syntacticErrors);
        }

        ZigEduParser parser = new ZigEduParser(new StringReader(safeSource));
        try {
            Ast.Program program = parser.ParseProgram();
            syntacticErrors.addAll(parser.getRecoveredErrors().stream()
                    .map(error -> toSyntacticDiagnostic(error, safeSource))
                    .toList());
            Optional<SemanticResult> semantic = syntacticErrors.isEmpty()
                    ? Optional.of(new SemanticAnalyzer(safeSource, tokens).analyze(program))
                    : Optional.empty();
            return new AnalysisResult(tokens, Optional.of(program), lexicalErrors, syntacticErrors, semantic);
        } catch (ParseException error) {
            syntacticErrors.addAll(parser.getRecoveredErrors().stream()
                    .map(recovered -> toSyntacticDiagnostic(recovered, safeSource))
                    .toList());
            syntacticErrors.add(toSyntacticDiagnostic(error, safeSource));
        } catch (TokenMgrError error) {
            lexicalErrors.add(toLexicalDiagnostic(error, safeSource));
        }
        return new AnalysisResult(tokens, Optional.empty(), lexicalErrors, syntacticErrors);
    }

    private void collectTokens(
            String source,
            List<TokenInfo> tokens,
            List<Diagnostic> lexicalErrors
    ) {
        int number = 1;
        int segmentOffset = 0;

        while (segmentOffset <= source.length()) {
            String segment = source.substring(segmentOffset);
            SourcePosition base = positionAtOffset(source, segmentOffset);
            ZigEduParser parser = new ZigEduParser(new StringReader(segment));
            try {
                Token token;
                do {
                    token = parser.getNextToken();
                    boolean malformedNumber = token.kind == ZigEduParserConstants.MALFORMED_NUMBER;
                    tokens.add(new TokenInfo(
                            number++,
                            token.kind == ZigEduParserConstants.EOF ? "<EOF>" : token.image,
                            malformedNumber ? "ERROR_LEXICO" : tokenName(token.kind),
                            globalLine(base, token.beginLine),
                            globalColumn(base, token.beginLine, token.beginColumn),
                            globalLine(base, token.endLine),
                            globalColumn(base, token.endLine, token.endColumn)
                    ));
                    if (malformedNumber) {
                        lexicalErrors.add(new Diagnostic(
                                Diagnostic.Phase.LEXICAL,
                                "El formato del literal numérico no pertenece al subconjunto soportado.",
                                globalLine(base, token.beginLine),
                                globalColumn(base, token.beginLine, token.beginColumn),
                                "Un entero decimal sin separadores o un decimal con punto o exponente",
                                "'" + escape(token.image) + "'",
                                sourceLine(source, globalLine(base, token.beginLine))
                        ));
                    }
                } while (token.kind != ZigEduParserConstants.EOF);
                return;
            } catch (TokenMgrError error) {
                Diagnostic local = toLexicalDiagnostic(error, segment);
                int errorOffset = offsetAtLineAndColumn(segment, local.line(), local.column());
                if (errorOffset < 0 || errorOffset > segment.length()) {
                    return;
                }

                int absoluteErrorOffset = segmentOffset + errorOffset;
                InvalidLexeme invalid = invalidLexemeAt(source, absoluteErrorOffset);
                if (invalid == null) {
                    return;
                }
                SourcePosition invalidStart = positionAtOffset(source, invalid.startOffset());
                SourcePosition invalidEnd = positionAtOffset(source, invalid.lastCharacterOffset());
                int removedTokens = removeTokensInside(tokens, source, invalid.startOffset(), invalid.endOffset());
                number -= removedTokens;

                String offendingCharacter = absoluteErrorOffset >= source.length()
                        ? "<EOF>"
                        : new String(Character.toChars(source.codePointAt(absoluteErrorOffset)));
                Diagnostic global = new Diagnostic(
                        local.phase(),
                        invalid.characterLiteral()
                                ? "El literal de carácter está incompleto o contiene una secuencia no permitida."
                                : "El lexema completo es inválido porque contiene un carácter no permitido.",
                        invalidStart.line(),
                        invalidStart.column(),
                        invalid.characterLiteral()
                                ? "Un carácter o escape válido entre comillas simples"
                                : local.expected(),
                        "'" + escape(invalid.text()) + "' (carácter causante: '"
                                + escape(offendingCharacter) + "')",
                        sourceLine(source, invalidStart.line())
                );
                lexicalErrors.add(global);
                tokens.add(new TokenInfo(
                        number++,
                        invalid.text(),
                        "ERROR_LEXICO",
                        invalidStart.line(),
                        invalidStart.column(),
                        invalidEnd.line(),
                        invalidEnd.column()
                ));
                segmentOffset = invalid.endOffset();
            }
        }
    }

    private InvalidLexeme invalidLexemeAt(String source, int errorOffset) {
        InvalidLexeme characterLiteral = invalidCharacterLiteralAt(source, errorOffset);
        if (characterLiteral != null) {
            return characterLiteral;
        }
        if (errorOffset >= source.length()) {
            return null;
        }

        int invalidCodePoint = source.codePointAt(errorOffset);
        int start = errorOffset;
        int end = errorOffset + Character.charCount(invalidCodePoint);

        if (Character.isUnicodeIdentifierPart(invalidCodePoint)) {
            while (start > 0) {
                int previous = source.codePointBefore(start);
                if (!Character.isUnicodeIdentifierPart(previous)) {
                    break;
                }
                start -= Character.charCount(previous);
            }
            while (end < source.length()) {
                int next = source.codePointAt(end);
                if (!Character.isUnicodeIdentifierPart(next)) {
                    break;
                }
                end += Character.charCount(next);
            }
        }

        int lastCharacterOffset = end - Character.charCount(source.codePointBefore(end));
        return new InvalidLexeme(start, end, lastCharacterOffset, source.substring(start, end), false);
    }

    private InvalidLexeme invalidCharacterLiteralAt(String source, int errorOffset) {
        int boundedOffset = Math.min(errorOffset, source.length());
        int lineStart = boundedOffset;
        while (lineStart > 0) {
            char previous = source.charAt(lineStart - 1);
            if (previous == '\n' || previous == '\r') {
                break;
            }
            lineStart--;
        }

        int openingQuote = -1;
        boolean escaped = false;
        for (int index = lineStart; index < boundedOffset; index++) {
            char character = source.charAt(index);
            if (character == '\\' && openingQuote >= 0 && !escaped) {
                escaped = true;
                continue;
            }
            if (character == '\'' && !escaped) {
                openingQuote = openingQuote < 0 ? index : -1;
            }
            escaped = false;
        }
        if (openingQuote < 0) {
            return null;
        }

        int end = boundedOffset;
        boolean closingEscaped = false;
        while (end < source.length()) {
            char character = source.charAt(end);
            if (character == '\n' || character == '\r') {
                break;
            }
            end++;
            if (character == '\\' && !closingEscaped) {
                closingEscaped = true;
                continue;
            }
            if (character == '\'' && !closingEscaped) {
                break;
            }
            closingEscaped = false;
        }
        if (end <= openingQuote) {
            return null;
        }
        int lastCharacterOffset = end - Character.charCount(source.codePointBefore(end));
        return new InvalidLexeme(
                openingQuote,
                end,
                lastCharacterOffset,
                source.substring(openingQuote, end),
                true
        );
    }

    private int removeTokensInside(List<TokenInfo> tokens, String source, int startOffset, int endOffset) {
        int removed = 0;
        while (!tokens.isEmpty()) {
            TokenInfo last = tokens.getLast();
            int tokenStart = offsetAtLineAndColumn(source, last.line(), last.column());
            if (tokenStart < startOffset || tokenStart >= endOffset) {
                break;
            }
            tokens.removeLast();
            removed++;
        }
        return removed;
    }

    private int globalLine(SourcePosition base, int localLine) {
        return base.line() + Math.max(localLine, 1) - 1;
    }

    private int globalColumn(SourcePosition base, int localLine, int localColumn) {
        return localLine <= 1
                ? base.column() + Math.max(localColumn, 1) - 1
                : Math.max(localColumn, 1);
    }

    private SourcePosition positionAtOffset(String source, int targetOffset) {
        int line = 1;
        int column = 1;
        int index = 0;
        while (index < targetOffset && index < source.length()) {
            char character = source.charAt(index++);
            if (character == '\r') {
                if (index < targetOffset && index < source.length() && source.charAt(index) == '\n') {
                    index++;
                }
                line++;
                column = 1;
            } else if (character == '\n') {
                line++;
                column = 1;
            } else if (character == '\t') {
                column++; // Mismo tabSize=1 que SimpleCharStream de JavaCC 7.
            } else {
                column++;
            }
        }
        return new SourcePosition(line, column);
    }

    private int offsetAtLineAndColumn(String source, int targetLine, int targetColumn) {
        int line = 1;
        int column = 1;
        int index = 0;
        while (index < source.length()) {
            if (line == targetLine && column >= targetColumn) {
                return index;
            }
            char character = source.charAt(index++);
            if (character == '\r') {
                if (index < source.length() && source.charAt(index) == '\n') {
                    index++;
                }
                line++;
                column = 1;
            } else if (character == '\n') {
                line++;
                column = 1;
            } else if (character == '\t') {
                column++;
            } else {
                column++;
            }
        }
        return line == targetLine && column >= targetColumn ? index : -1;
    }

    private Diagnostic toLexicalDiagnostic(TokenMgrError error, String source) {
        String message = error.getMessage() == null ? "Error léxico desconocido" : error.getMessage();
        Matcher matcher = LEXICAL_POSITION.matcher(message);
        int line = 1;
        int column = 1;
        String found = message;
        if (matcher.find()) {
            line = Integer.parseInt(matcher.group(1));
            column = Integer.parseInt(matcher.group(2));
            found = matcher.group(3).strip();
        }
        return new Diagnostic(
                Diagnostic.Phase.LEXICAL,
                "El analizador no pudo formar un token válido.",
                line,
                column,
                "Un carácter o lexema válido del lenguaje",
                found,
                sourceLine(source, line)
        );
    }

    private Diagnostic toSyntacticDiagnostic(ParseException error, String source) {
        Token foundToken = error.currentToken == null ? null : error.currentToken.next;
        int line = foundToken == null ? 1 : Math.max(foundToken.beginLine, 1);
        int column = foundToken == null ? 1 : Math.max(foundToken.beginColumn, 1);
        String found = foundToken == null
                ? "<EOF>"
                : tokenName(foundToken.kind) + " '" + escape(foundToken.image) + "'";

        Set<String> expected = new LinkedHashSet<>();
        if (error.expectedTokenSequences != null && error.tokenImage != null) {
            for (int[] sequence : error.expectedTokenSequences) {
                if (sequence.length == 0) {
                    continue;
                }
                StringBuilder item = new StringBuilder();
                for (int kind : sequence) {
                    if (!item.isEmpty()) {
                        item.append(' ');
                    }
                    item.append(displayToken(error.tokenImage[kind]));
                }
                expected.add(item.toString());
            }
        }

        return new Diagnostic(
                Diagnostic.Phase.SYNTACTIC,
                "La secuencia de tokens no coincide con la gramática del subconjunto.",
                line,
                column,
                expected.isEmpty() ? "Una continuación válida" : String.join(" | ", expected),
                found,
                sourceLine(source, line)
        );
    }

    private String tokenName(int kind) {
        if (kind == ZigEduParserConstants.LEN) {
            return "IDENTIFIER";
        }
        if (kind < 0 || kind >= TOKEN_NAMES.length) {
            return "TOKEN_" + kind;
        }
        return TOKEN_NAMES[kind];
    }

    private static String[] buildTokenNames() {
        String[] names = new String[ZigEduParserConstants.tokenImage.length];
        Arrays.fill(names, "TOKEN");
        for (Field field : ZigEduParserConstants.class.getFields()) {
            if (field.getType() != int.class || !Modifier.isStatic(field.getModifiers())
                    || field.getName().equals("DEFAULT")) {
                continue;
            }
            try {
                int kind = field.getInt(null);
                if (kind >= 0 && kind < names.length) {
                    names[kind] = field.getName();
                }
            } catch (IllegalAccessException exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }
        names[ZigEduParserConstants.EOF] = "EOF";
        return names;
    }

    private String displayToken(String tokenImage) {
        if (tokenImage == null) {
            return "TOKEN";
        }
        if (tokenImage.startsWith("<") && tokenImage.endsWith(">")) {
            return tokenImage.substring(1, tokenImage.length() - 1);
        }
        return tokenImage.replace("\\\"", "\"");
    }

    private String sourceLine(String source, int line) {
        String[] lines = source.split("\\R", -1);
        return line >= 1 && line <= lines.length ? lines[line - 1] : "";
    }

    private String escape(String value) {
        return value == null ? "" : value
                .replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private record SourcePosition(int line, int column) {
    }

    private record InvalidLexeme(
            int startOffset,
            int endOffset,
            int lastCharacterOffset,
            String text,
            boolean characterLiteral
    ) {
    }
}

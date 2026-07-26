package com.yuukatts.tokenizer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BERT Japanese WordPiece tokenizer。
 * 从 cl-tohoku/bert-base-japanese 的 vocab.txt 加载词表。
 *
 * 参考 HuggingFace tokenizers 的 WordPiece 算法：
 * - 先按字符拆分
 * - 贪心最长匹配
 * - 未知字符用 [UNK]
 */
public class WordPieceTokenizer {

    private static final String CLS = "[CLS]";
    private static final String SEP = "[SEP]";
    private static final String UNK = "[UNK]";
    private static final String PAD = "[PAD]";

    private int clsId;
    private int sepId;
    private int unkId;
    private int padId;

    private final Map<String, Integer> vocab = new HashMap<>();
    private final Map<Integer, String> idToToken = new HashMap<>();
    private int maxTokenLen = 0;

    public void loadVocab(InputStream vocabStream) throws IOException {
        vocab.clear();
        idToToken.clear();
        BufferedReader reader = new BufferedReader(new InputStreamReader(vocabStream, "UTF-8"));
        String line;
        int idx = 0;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            vocab.put(line, idx);
            idToToken.put(idx, line);
            if (line.length() > maxTokenLen) maxTokenLen = line.length();
            idx++;
        }
        reader.close();

        clsId = vocab.getOrDefault(CLS, 2);
        sepId = vocab.getOrDefault(SEP, 3);
        unkId = vocab.getOrDefault(UNK, 1);
        padId = vocab.getOrDefault(PAD, 0);
    }

    public void loadVocab(Map<String, Integer> externalVocab) {
        vocab.clear();
        idToToken.clear();
        vocab.putAll(externalVocab);
        for (Map.Entry<String, Integer> e : externalVocab.entrySet()) {
            idToToken.put(e.getValue(), e.getKey());
            if (e.getKey().length() > maxTokenLen) maxTokenLen = e.getKey().length();
        }
        clsId = vocab.getOrDefault(CLS, 2);
        sepId = vocab.getOrDefault(SEP, 3);
        unkId = vocab.getOrDefault(UNK, 1);
        padId = vocab.getOrDefault(PAD, 0);
    }

    public int getClsId() { return clsId; }
    public int getSepId() { return sepId; }
    public int getUnkId() { return unkId; }
    public int getPadId() { return padId; }
    public int getVocabSize() { return vocab.size(); }

    /**
     * Tokenize 日文文本，返回 BERT 输入格式的 token IDs。
     * 含 [CLS] 和 [SEP]。
     *
     * @param text 日文文本（如 "こんにちは"）
     * @param maxLen 最大长度（含 CLS+SEP），超出截断
     * @return token ID 数组
     */
    public int[] encode(String text, int maxLen) {
        List<Integer> tokens = new ArrayList<>();
        tokens.add(clsId);

        // 基础分词：按字符拆分后用 WordPiece 贪心匹配
        List<String> preTokens = basicTokenize(text);
        for (String token : preTokens) {
            tokenizeWord(token, tokens);
        }

        tokens.add(sepId);

        // 截断
        if (tokens.size() > maxLen) {
            tokens = tokens.subList(0, maxLen - 1);
            tokens.add(sepId);
        }

        int[] ids = new int[tokens.size()];
        for (int i = 0; i < tokens.size(); i++) ids[i] = tokens.get(i);
        return ids;
    }

    /**
     * 日文基础分句：按字符 + 标点拆分。
     * 日文没有空格分词，我们按每个字符（或标点组合）分成 pre-tokens，
     * 然后 WordPiece 再细分。
     */
    private List<String> basicTokenize(String text) {
        List<String> result = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int charLen = Character.charCount(cp);

            // 标点和特殊字符单独拆分
            if (Character.isWhitespace(cp)) {
                if (buf.length() > 0) { result.add(buf.toString()); buf.setLength(0); }
                i += charLen;
                continue;
            }

            if (isPunctuation(cp)) {
                if (buf.length() > 0) { result.add(buf.toString()); buf.setLength(0); }
                result.add(new String(Character.toChars(cp)));
                i += charLen;
                continue;
            }

            // CJK 字符（汉字、假名、韩文等）每个单独作为 pre-token
            if (isCJK(cp)) {
                if (buf.length() > 0) { result.add(buf.toString()); buf.setLength(0); }
                result.add(new String(Character.toChars(cp)));
            } else {
                // 英文/数字等连续字符合并
                buf.append(Character.toChars(cp));
            }
            i += charLen;
        }
        if (buf.length() > 0) result.add(buf.toString());

        return result;
    }

    /**
     * WordPiece 贪心最长匹配。
     * 对单个 pre-token 做子词切分。
     */
    private void tokenizeWord(String word, List<Integer> output) {
        if (word.isEmpty()) return;

        // 先尝试直接命中
        if (vocab.containsKey(word)) {
            output.add(vocab.get(word));
            return;
        }

        // 全角 → 半角（日文常见）
        String normalized = normalizeJapanese(word);
        if (!normalized.equals(word) && vocab.containsKey(normalized)) {
            output.add(vocab.get(normalized));
            return;
        }

        // WordPiece 贪心切分
        int start = 0;
        while (start < word.length()) {
            int end = word.length();
            boolean found = false;

            while (end > start) {
                String sub = (start > 0 ? "##" : "") + word.substring(start, end);
                if (vocab.containsKey(sub)) {
                    output.add(vocab.get(sub));
                    found = true;
                    break;
                }
                // 也尝试小写
                if (vocab.containsKey(sub.toLowerCase())) {
                    output.add(vocab.get(sub.toLowerCase()));
                    found = true;
                    break;
                }
                end--;
            }

            if (!found) {
                output.add(unkId);
                break;
            }
            start = end;
        }
    }

    private boolean isCJK(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)   // CJK Unified
            || (cp >= 0x3040 && cp <= 0x309F)   // Hiragana
            || (cp >= 0x30A0 && cp <= 0x30FF)   // Katakana
            || (cp >= 0x3400 && cp <= 0x4DBF)   // CJK Extension A
            || (cp >= 0xAC00 && cp <= 0xD7AF)   // Hangul
            || (cp >= 0xFF66 && cp <= 0xFF9F);  // Halfwidth Katakana
    }

    private boolean isPunctuation(int cp) {
        int type = Character.getType(cp);
        return type == Character.START_PUNCTUATION
            || type == Character.END_PUNCTUATION
            || type == Character.INITIAL_QUOTE_PUNCTUATION
            || type == Character.FINAL_QUOTE_PUNCTUATION
            || type == Character.OTHER_PUNCTUATION
            || type == Character.CONNECTOR_PUNCTUATION
            || type == Character.DASH_PUNCTUATION;
    }

    /**
     * 日文全角→半角标准化。
     */
    private String normalizeJapanese(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0xFF01 && c <= 0xFF5E) {
                sb.append((char) (c - 0xFEE0));
            } else if (c == 0x3000) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 为参考文本编码，返回整个序列（含 CLS + 文本 + SEP）。
     */
    public int[] encodeReference(String text, int maxLen) {
        return encode(text, maxLen);
    }
}

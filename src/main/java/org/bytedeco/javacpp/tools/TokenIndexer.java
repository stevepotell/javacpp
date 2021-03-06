/*
 * Copyright (C) 2014 Samuel Audet
 *
 * This file is part of JavaCPP.
 *
 * JavaCPP is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version (subject to the "Classpath" exception
 * as provided in the LICENSE.txt file that accompanied this code).
 *
 * JavaCPP is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with JavaCPP.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bytedeco.javacpp.tools;

import java.io.IOException;
import java.util.ArrayList;

/**
 *
 * @author Samuel Audet
 */
class TokenIndexer {
    TokenIndexer(InfoMap infoMap, Token[] array) {
        this.infoMap = infoMap;
        this.array = array;
    }

    boolean raw = false;
    InfoMap infoMap = null;
    Token[] array = null;
    int index = 0;

    void filter(int index) {
        if (index + 1 < array.length && array[index].match('#') &&
                array[index + 1].match(Token.IF, Token.IFDEF, Token.IFNDEF)) {
            ArrayList<Token> tokens = new ArrayList<Token>();
            for (int i = 0; i < index; i++) {
                tokens.add(array[i]);
            }
            int count = 0;
            Info info = null;
            boolean define = true, defined = false;
            while (index < array.length) {
                Token keyword = null;
                if (array[index].match('#')) {
                    if (array[index + 1].match(Token.IF, Token.IFDEF, Token.IFNDEF)) {
                        count++;
                    }
                    if (count == 1 && array[index + 1].match(Token.IF, Token.IFDEF, Token.IFNDEF, Token.ELIF, Token.ELSE, Token.ENDIF)) {
                        keyword = array[index + 1];
                    }
                    if (array[index + 1].match(Token.ENDIF)) {
                        count--;
                    }
                }
                if (keyword != null) {
                    tokens.add(array[index++]);
                    tokens.add(array[index++]);
                    if (keyword.match(Token.IF, Token.IFDEF, Token.IFNDEF, Token.ELIF)) {
                        String value = "";
                        while (index < array.length) {
                            if (array[index].spacing.indexOf('\n') >= 0) {
                                break;
                            }
                            value += array[index].spacing + array[index];
                            tokens.add(array[index++]);
                        }
                        define = info == null || !defined;
                        info = infoMap.getFirst(value);
                        if (info != null) {
                            define = keyword.match(Token.IFNDEF) ? !info.define : info.define;
                        } else try {
                            define = Integer.parseInt(value.trim()) != 0;
                        } catch (NumberFormatException e) {
                            /* default define */
                        }
                    } else if (keyword.match(Token.ELSE)) {
                        define = info == null || !define;
                    } else if (keyword.match(Token.ENDIF)) {
                        if (count == 0) {
                            break;
                        }
                    }
                } else if (define) {
                    tokens.add(array[index++]);
                } else {
                    index++;
                }
                defined = define || defined;
            }
            while (index < array.length) {
                tokens.add(array[index++]);
            }
            array = tokens.toArray(new Token[tokens.size()]);
        }
    }

    void expand(int index) {
        if (index < array.length && infoMap.containsKey(array[index].value)) {
            int startIndex = index;
            Info info = infoMap.getFirst(array[index].value);
            if (info != null && info.cppText != null) {
                try {
                    Tokenizer tokenizer = new Tokenizer(info.cppText);
                    if (!tokenizer.nextToken().match('#')
                            || !tokenizer.nextToken().match(Token.DEFINE)
                            || !tokenizer.nextToken().match(info.cppNames[0])) {
                        return;
                    }
                    ArrayList<Token> tokens = new ArrayList<Token>();
                    for (int i = 0; i < index; i++) {
                        tokens.add(array[i]);
                    }
                    ArrayList<String> params = new ArrayList<String>();
                    ArrayList<Token>[] args = null;
                    Token token = tokenizer.nextToken();
                    if (token.match('(')) {
                        token = tokenizer.nextToken();
                        while (!token.isEmpty()) {
                            if (token.match(Token.IDENTIFIER)) {
                                params.add(token.value);
                            } else if (token.match(')')) {
                                token = tokenizer.nextToken();
                                break;
                            }
                            token = tokenizer.nextToken();
                        }
                        index++;
                        if (params.size() > 0 && (index >= array.length || !array[index].match('('))) {
                            return;
                        }
                        args = new ArrayList[params.size()];
                        int count = 0, count2 = 0;
                        for (index++; index < array.length; index++) {
                            Token token2 = array[index];
                            if (count2 == 0 && token2.match(')')) {
                                break;
                            } else if (count2 == 0 && token2.match(',')) {
                                count++;
                                continue;
                            } else if (token2.match('(','[','{')) {
                                count2++;
                            } else if (token2.match(')',']','}')) {
                                count2--;
                            }
                            if (args[count] == null) {
                                args[count] = new ArrayList<Token>();
                            }
                            args[count].add(token2);
                        }
                    }
                    while (!token.isEmpty()) {
                        boolean foundArg = false;
                        for (int i = 0; i < params.size(); i++) {
                            if (params.get(i).equals(token.value)) {
                                if (tokens.size() == startIndex) {
                                    args[i].get(0).spacing = array[startIndex].spacing;
                                }
                                tokens.addAll(args[i]);
                                foundArg = true;
                                break;
                            }
                        }
                        if (!foundArg) {
                            if (tokens.size() == startIndex) {
                                token.spacing = array[startIndex].spacing;
                            }
                            tokens.add(token);
                        }
                        token = tokenizer.nextToken();
                    }
                    for (index++; index < array.length; index++) {
                        tokens.add(array[index]);
                    }
                    array = tokens.toArray(new Token[tokens.size()]);
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }

    int preprocess(int index, int count) {
        while (index < array.length) {
            filter(index);
            expand(index);
            if (!array[index].match(Token.COMMENT) && --count < 0) {
                break;
            }
            index++;
        }
        filter(index);
        expand(index);
        return index;
    }

    Token get() {
        return get(0);
    }
    Token get(int i) {
        int k = raw ? index + i : preprocess(index, i);
        return k < array.length ? array[k] : Token.EOF;
    }
    Token next() {
        index = raw ? index + 1 : preprocess(index, 1);
        return index < array.length ? array[index] : Token.EOF;
    }
}

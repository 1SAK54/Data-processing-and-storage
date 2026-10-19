package ru.nsu.vorona;

import java.util.List;

/**
 * Данные одного ответа HTTP-сервера.
 *
 * @param message текст, который нужно включить в результат
 * @param successors пути ресурсов для дальнейшего обхода
 */
public record ServerResponse(String message, List<String> successors) { }

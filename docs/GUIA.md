# Guia para redactar los capitulos del manual de estudio

Cada capitulo es un **fragmento** `.tex` que `manual-estudio.tex` incluye con
`\input`. **No** escribas `\documentclass`, `\usepackage`, `\begin{document}`
ni `\end{document}`. Empieza directo con `\section{...}`.

El preambulo ya esta definido en `manual-estudio.tex`. Comandos y entornos
disponibles:

## Estructura
- `\section{Titulo}`, `\subsection{Titulo}`, `\subsubsection{Titulo}`.
- Cada capitulo: un `\section` y de 2 a 4 `\subsection`.

## Codigo
- En linea: `\codigo{texto}` para clases, metodos, rutas y variables de entorno.
  Ej: `\codigo{Bank.transferir()}`, `\codigo{TES\_LEADER\_HOST}`.
- En bloque (verbatim, NO se escapa nada adentro):

```latex
\begin{lstlisting}[language=Java,caption={Pie del listado}]
public Reply handle(Request r) {
    return Reply.json(200, cuerpo);
}
\end{lstlisting}
```

  Usa `language=Java` para Java y `language=none` para JSON, bash, HTTP u otros.
- Fragmentos CORTOS (5-15 lineas), **copiados del archivo real**, recortables
  con `// ...`. No inventes metodos, firmas ni APIs.

## Marcadores del scaffolding
- `\pendiente{descripcion}` -> sale en rojo "[PENDIENTE: ...]". **Reemplazalo**
  por prosa real. El trabajo termina cuando no queda ningun `\pendiente`.
- Comentarios `% GUIA:`, `% CITAR:`, `% SNIPPET:` indican que cubrir, que
  archivo citar y que fragmento copiar. Son instrucciones; puedes borrarlos al
  redactar.

## Escape (en PROSA y argumentos, NUNCA dentro de lstlisting)
Caracteres especiales -> escribir: `\#` `\%` `\&` `\_` `\{` `\}` `\$`,
`\textbackslash{}` para barra invertida, `\textasciitilde{}` para `~`.
Dentro de `lstlisting` el contenido es verbatim: NO escapes nada ahi.
Ojo con las variables de entorno: en prosa escribe `\codigo{TES\_BUCKET}`.

## Reglas de contenido
- Solo informacion REAL leida de los archivos fuente indicados en cada capitulo.
- Cita cada componente con `\codigo{ruta/Archivo.java}` al introducirlo.
- Prosa tecnica y concisa en espanol. Explica QUE hace, COMO y POR QUE.
- El acento/UTF-8 esta habilitado (`inputenc utf8`): puedes escribir en espanol
  con acentos normales en la prosa. El codigo Java va en ASCII tal cual del
  archivo.
- No describas funcionalidad que no exista en el codigo. Si algo no esta
  implementado, no lo documentes como si lo estuviera.

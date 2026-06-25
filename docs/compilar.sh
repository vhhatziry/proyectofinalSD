#!/bin/bash
# Compila el manual de estudio. Corre pdflatex dos veces (indice/referencias).
# Uso: cd ~/hatziry/docs && ./compilar.sh
set -e
cd "$(dirname "$0")"
echo "== pdflatex (pasada 1/2) =="
pdflatex -interaction=nonstopmode -halt-on-error manual-estudio.tex >/dev/null
echo "== pdflatex (pasada 2/2) =="
pdflatex -interaction=nonstopmode -halt-on-error manual-estudio.tex >/dev/null
echo "== listo: manual-estudio.pdf =="
# Limpia auxiliares
rm -f manual-estudio.aux manual-estudio.log manual-estudio.out manual-estudio.toc

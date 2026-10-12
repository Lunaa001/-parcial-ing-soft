#!/usr/bin/env bash
# Calcula la version semantica (vX.Y.Z) del commit actual. Lo usan versioning.yml (que crea el tag)
# y ciclo-azure.yml (que etiqueta la imagen), asi la regla esta escrita una sola vez y los dos
# llegan siempre al mismo resultado, aunque corran a la vez:
#   1. Si el commit ya tiene un tag vX.Y.Z, se usa ese (por ejemplo si versioning.yml termino antes).
#   2. Si no, se sube la ultima version segun el mensaje del commit (Conventional Commits):
#      "!" tras el tipo o "BREAKING CHANGE:" -> major (X); feat -> minor (Y); fix/refactor -> patch (Z).
#   3. Si el commit no sube version (docs, chore, ci, o un Run workflow sin mensaje), se usa la ultima.
# Necesita el historial y los tags (checkout con fetch-depth: 0). El mensaje llega por la variable
# COMMIT_MSG (nunca interpolado en el script, para evitar command injection).
# Escribe version, nueva (true si hay que crear el tag) y bump en $GITHUB_OUTPUT (o en la salida).
set -euo pipefail

COMMIT_MSG="${COMMIT_MSG:-}"
PATRON='v[0-9]*.[0-9]*.[0-9]*'

EN_HEAD=$(git tag --points-at HEAD --list "$PATRON" --sort=-v:refname | head -n1)
LAST_TAG=$(git tag --list "$PATRON" --sort=-v:refname | head -n1)
LAST_TAG=${LAST_TAG:-v0.0.0}

BUMP=""
if [[ "$COMMIT_MSG" =~ ^[a-z]+(\(.*\))?!: ]] || [[ "$COMMIT_MSG" == *"BREAKING CHANGE:"* ]]; then
  BUMP="major"
elif [[ "$COMMIT_MSG" =~ ^feat(\(.*\))?: ]]; then
  BUMP="minor"
elif [[ "$COMMIT_MSG" =~ ^(fix|refactor)(\(.*\))?: ]]; then
  BUMP="patch"
fi

if [[ -n "$EN_HEAD" ]]; then
  VERSION="$EN_HEAD"
  NUEVA=false
  echo "El commit ya tiene el tag $VERSION."
elif [[ -z "$BUMP" ]]; then
  VERSION="$LAST_TAG"
  NUEVA=false
  echo "El commit no sube version (doc/chore/sin prefijo reconocido): se usa la ultima, $VERSION."
else
  IFS='.' read -r MAJOR MINOR PATCH <<< "${LAST_TAG#v}"
  if [[ "$BUMP" == "major" ]]; then
    MAJOR=$((MAJOR + 1)); MINOR=0; PATCH=0
  elif [[ "$BUMP" == "minor" ]]; then
    MINOR=$((MINOR + 1)); PATCH=0
  else
    PATCH=$((PATCH + 1))
  fi
  VERSION="v${MAJOR}.${MINOR}.${PATCH}"
  NUEVA=true
  echo "Ultimo tag: $LAST_TAG -> Nuevo tag: $VERSION (bump: $BUMP)"
fi

SALIDA="${GITHUB_OUTPUT:-/dev/stdout}"
{
  echo "version=$VERSION"
  echo "nueva=$NUEVA"
  echo "bump=${BUMP:-ninguno}"
} >> "$SALIDA"

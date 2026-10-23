FROM node:24-alpine AS build
WORKDIR /src
COPY package.json package-lock.json .npmrc ./
RUN npm ci
COPY . .
RUN npm run build-only

FROM nginx:1.29-alpine
# The official image renders templates with envsubst at startup (only defined vars, so $uri etc. survive).
ENV BACKEND_UPSTREAM=backend:8080
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /src/dist /usr/share/nginx/html

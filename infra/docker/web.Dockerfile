FROM node:22-alpine AS build
WORKDIR /app
ARG VITE_GIT_SHA=dev
ENV VITE_GIT_SHA=${VITE_GIT_SHA}
COPY web/package*.json ./
RUN if [ -f package-lock.json ]; then npm ci; else npm install; fi
COPY web/ ./
RUN npm run build

FROM nginx:1.29-alpine
COPY infra/docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/ /usr/share/nginx/html/
EXPOSE 80

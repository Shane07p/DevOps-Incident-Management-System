# Build the static bundle, then serve it with nginx.
FROM node:24-alpine AS build
WORKDIR /build
COPY package.json package-lock.json ./
# Cypress runs on the CI runner; the UI image only needs its build dependencies.
RUN CYPRESS_INSTALL_BINARY=0 npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80

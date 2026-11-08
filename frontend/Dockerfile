FROM node:24-alpine AS build
ARG VITE_MAX_UPLOAD_MIB=10
ARG VITE_CHM_UPLOAD_ENABLED=false
ENV VITE_MAX_UPLOAD_MIB=$VITE_MAX_UPLOAD_MIB VITE_CHM_UPLOAD_ENABLED=$VITE_CHM_UPLOAD_ENABLED
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80

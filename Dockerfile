FROM node:20-alpine
WORKDIR /app
COPY backoffice/server/package*.json ./
RUN npm install --omit=dev
COPY backoffice/server/ .
COPY backoffice/index.html ./public/index.html
EXPOSE 3000
CMD ["node", "index.js"]

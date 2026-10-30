FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.* ./
COPY *.go ./
# go mod tidy resolves the latest compatible deps and writes go.sum when it is not committed yet.
RUN go mod tidy && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/app .

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/app /app
EXPOSE 8080
ENTRYPOINT ["/app"]

# Stages: deps -> test (docker build --target test .) | build -> runtime (default target)
FROM golang:1.27-alpine AS deps
ENV CGO_ENABLED=0
WORKDIR /src
COPY go.* ./
COPY *.go ./
# go mod tidy resolves the latest compatible deps and writes go.sum when it is not committed yet.
RUN go mod tidy && go mod download

# Formatting, static analysis and unit tests (*_test.go). CI runs this before building the image.
FROM deps AS test
RUN unformatted="$(gofmt -l .)" && \
    if [ -n "$unformatted" ]; then echo "gofmt needed: $unformatted"; exit 1; fi && \
    go vet ./... && \
    go test -count=1 ./...

FROM deps AS build
RUN go build -trimpath -ldflags="-s -w" -o /out/app .

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/app /app
EXPOSE 8080
ENTRYPOINT ["/app"]

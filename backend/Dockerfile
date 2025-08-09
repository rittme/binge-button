# Use Alpine Linux as base image for minimal size
FROM golang AS build

# Set working directory
WORKDIR /app

# Copy go mod files first for better caching
COPY go.mod go.sum ./

# Download dependencies
RUN go mod download

# Copy source code
COPY . .

# Build the application
RUN CGO_ENABLED=0 GOOS=linux go build -a -installsuffix cgo -o main .

# Use a minimal Alpine image for the final stage
FROM alpine:latest  

# Install ca-certificates for HTTPS requests
RUN apk --no-cache add ca-certificates tzdata

LABEL org.opencontainers.image.authors="Bernardo Rittmeyer"
LABEL org.opencontainers.image.source=https://github.com/rittme/comfort-player
LABEL org.opencontainers.image.description="Comfort Player Server"
LABEL org.opencontainers.image.licenses=MIT


# Create non-root user for security
RUN addgroup -g 1000 appuser && \
    adduser -D -s /bin/sh -u 1000 -G appuser appuser

# Create necessary directories
RUN mkdir -p /app/data /app/media/shows && \
    chown -R appuser:appuser /app

# Set working directory
WORKDIR /app

# Copy the binary from builder stage
COPY --from=build /app/main .

# Change ownership of the binary
RUN chown appuser:appuser /app/main

# Switch to non-root user
USER appuser

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/api/show/info || exit 1

# Run the application
CMD ["./main"]

package main

import (
	"io"
	"net/http"
	"os"
	"strings"
	"time"
)

func detectRegion() string {
	// 1. Try Metadata API
	client := &http.Client{Timeout: 2 * time.Second}
	req, err := http.NewRequest("GET", "http://metadata.google.internal/computeMetadata/v1/instance/zone", nil)
	if err == nil {
		req.Header.Add("Metadata-Flavor", "Google")
		resp, err := client.Do(req)
		if err == nil && resp.StatusCode == 200 {
			defer resp.Body.Close()
			body, err := io.ReadAll(resp.Body)
			if err == nil {
				// Body format: projects/123/zones/us-central1-a
				parts := strings.Split(string(body), "/")
				zone := parts[len(parts)-1]
				// Region is zone without the last dash part (e.g. us-central1-a -> us-central1)
				if lastDash := strings.LastIndex(zone, "-"); lastDash != -1 {
					return zone[:lastDash]
				}
				return zone
			}
		}
	}

	// 2. Fallback to Env Var
	if region := os.Getenv("RBS_REGION"); region != "" {
		return region
	}

	// 3. Default
	return "default"
}

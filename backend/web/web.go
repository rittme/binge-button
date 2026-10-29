// Package web holds the static pages served by the backend.
package web

import _ "embed"

// SettingsPage is the configuration page served at /settings.
//
//go:embed settings.html
var SettingsPage []byte

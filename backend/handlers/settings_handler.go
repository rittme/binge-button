package handlers

import (
	"encoding/json"
	"errors"
	"log"
	"net/http"

	"github.com/gorilla/mux"

	"comfort-player-backend/services"
	"comfort-player-backend/web"
)

// SettingsHandler serves the settings page and its device/remote API.
type SettingsHandler struct {
	devices *services.DeviceService
	remote  *services.RemoteService
}

// NewSettingsHandler creates a new settings handler.
func NewSettingsHandler(devices *services.DeviceService, remote *services.RemoteService) *SettingsHandler {
	return &SettingsHandler{devices: devices, remote: remote}
}

// SettingsPage handles GET /settings
func (h *SettingsHandler) SettingsPage(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Write(web.SettingsPage)
}

// ListDevices handles GET /api/settings/devices
func (h *SettingsHandler) ListDevices(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"devices": h.devices.List()})
}

// UpdateDevice handles POST /api/settings/devices/{id}
func (h *SettingsHandler) UpdateDevice(w http.ResponseWriter, r *http.Request) {
	var req struct {
		IsProjector *bool `json:"isProjector"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "Invalid JSON")
		return
	}
	id := mux.Vars(r)["id"]
	d, err := h.devices.Get(id)
	if err == nil && req.IsProjector != nil {
		d, err = h.devices.SetProjector(id, *req.IsProjector)
	}
	if err != nil {
		h.writeServiceError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, d)
}

// StartPairing handles POST /api/settings/devices/{id}/pair/start
func (h *SettingsHandler) StartPairing(w http.ResponseWriter, r *http.Request) {
	if err := h.remote.StartPairing(r.Context(), mux.Vars(r)["id"]); err != nil {
		h.writeServiceError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"success": true})
}

// FinishPairing handles POST /api/settings/devices/{id}/pair/finish
func (h *SettingsHandler) FinishPairing(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Code string `json:"code"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "Invalid JSON")
		return
	}
	id := mux.Vars(r)["id"]
	if err := h.remote.FinishPairing(id, req.Code); err != nil {
		h.writeServiceError(w, err)
		return
	}
	d, err := h.devices.Get(id)
	if err != nil {
		h.writeServiceError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, d)
}

// PowerOffDevice handles POST /api/settings/devices/{id}/power-off
func (h *SettingsHandler) PowerOffDevice(w http.ResponseWriter, r *http.Request) {
	h.powerOff(w, r, mux.Vars(r)["id"])
}

// PowerOffCaller handles POST /api/remote/power-off: turns off the device
// that sent the request (the TV running the app).
func (h *SettingsHandler) PowerOffCaller(w http.ResponseWriter, r *http.Request) {
	d, err := h.devices.FindByIP(services.ClientIP(r))
	if err != nil {
		h.writeServiceError(w, err)
		return
	}
	h.powerOff(w, r, d.ID)
}

func (h *SettingsHandler) powerOff(w http.ResponseWriter, r *http.Request, id string) {
	result, err := h.remote.PowerOff(r.Context(), id)
	if err != nil {
		h.writeServiceError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"success": true, "result": result})
}

func (h *SettingsHandler) writeServiceError(w http.ResponseWriter, err error) {
	status := http.StatusInternalServerError
	switch {
	case errors.Is(err, services.ErrDeviceNotFound):
		status = http.StatusNotFound
	case errors.Is(err, services.ErrWrongCode):
		status = http.StatusBadRequest
	case errors.Is(err, services.ErrNoPairingSession):
		status = http.StatusGone
	case errors.Is(err, services.ErrNotPaired):
		status = http.StatusConflict
	case errors.Is(err, services.ErrUnreachable):
		status = http.StatusBadGateway
	}
	if status == http.StatusInternalServerError {
		log.Printf("SettingsHandler: %v", err)
	}
	writeError(w, status, err.Error())
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

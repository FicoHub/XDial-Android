//go:build android

package libbox

import (
	"encoding/json"
	"fmt"
	"github.com/kafeifei/xdial/core/config"
)

// AndroidStartProfile shares the existing mobile compiler and protocol runtime.
// Unsupported macOS process rules are rejected rather than silently ignored.
func (l *Libbox) AndroidStartProfile(profileJSON, basePath string) error {
	p, err := config.ParseRuntimeProfile([]byte(profileJSON))
	if err != nil {
		return err
	}
	plan, err := config.BuildConnectionPlan(p)
	if err != nil {
		return err
	}
	for _, t := range plan.Tasks {
		if t.ResourceType == string(config.RuleSetTypeApplication) {
			return fmt.Errorf("Android cannot apply macOS application rules")
		}
	}
	generated, err := GenerateNEConfig(profileJSON, "", basePath)
	if err != nil {
		return err
	}
	// Match Builder's MTU; both IP families remain captured.
	var obj map[string]interface{}
	if err = json.Unmarshal([]byte(generated), &obj); err != nil {
		return err
	}
	for _, in := range obj["inbounds"].([]interface{}) {
		m := in.(map[string]interface{})
		if m["type"] == "tun" {
			m["mtu"] = 1500
		}
	}
	data, err := json.Marshal(obj)
	if err != nil {
		return err
	}
	for _, t := range plan.Tasks {
		if t.Kind == config.ConnectionTaskLine && t.ResourceType == "vpn" {
			line := p.FindLine(t.ResourceID)
			if line == nil {
				return fmt.Errorf("active AnyConnect line is missing")
			}
			return l.StartResolvedWithInsecure(line.VPNServer, "", line.VPNUsername, line.VPNPassword, line.AllowInsecure, string(data))
		}
	}
	return l.StartStandalone(string(data))
}

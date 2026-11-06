//go:build !linux

package main

import (
	"errors"
	"os"
)

func OpenTUN(name string) (*os.File, error) { return nil, errors.New("TUN только на Linux") }

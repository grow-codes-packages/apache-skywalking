// Package skywalking is the Grow Codes SDK for Apache SkyWalking.
//
// Create a client from the environment (GROW_APACHE_SKYWALKING_URL,
// GROW_APACHE_SKYWALKING_TOKEN, ...) or from a Config literal:
//
//	client, err := skywalking.NewClientFromEnv()
//	if err != nil {
//		return err
//	}
//	defer client.Close()
//	status, err := client.Health(ctx)
//
// Every error is an *Error; compare codes with errors.Is against the Err*
// sentinels (errors.Is(err, skywalking.ErrNotFound)). Credentials are wrapped in
// Secret and never appear in errors, hooks or logs.
//
// Apache SkyWalking is a trademark of The Apache Software Foundation. This package is not affiliated
// with or endorsed by The Apache Software Foundation.
package skywalking
